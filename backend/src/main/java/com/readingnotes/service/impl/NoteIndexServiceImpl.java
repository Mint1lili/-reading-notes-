package com.readingnotes.service.impl;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.ai.DoubaoClient;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.service.NoteIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识回流服务实现（API 文档 §9.8 / 需求 §3.5）
 *
 * <p>笔记向量统一写入 Chroma {@code notes_all} 集合（数据库设计 §6）。
 * 切片规则：≤800 字单片；&gt;800 字按段落聚合切，单段超长硬切；单笔记上限 20 片。
 * 每个 chunk 的 metadata 携带 {@code note_id}，删除/恢复按 note_id 批量操作。</p>
 */
@Slf4j
@Service
public class NoteIndexServiceImpl implements NoteIndexService {

    /** 笔记回流集合（ChromaClient 类注释约定） */
    public static final String NOTES_COLLECTION = "notes_all";

    /** 长笔记切片阈值：超过 800 字才切（需求 §3.5） */
    private static final int CHUNK_THRESHOLD = 800;

    /** 单笔记切片上限：20 片 ≈ 16000 字（需求 §3.5 / API §9.8） */
    private static final int MAX_CHUNKS = 20;

    private final ChromaClient chroma;
    private final DoubaoClient doubao;
    private final NoteRepository noteRepository;

    public NoteIndexServiceImpl(ChromaClient chroma, DoubaoClient doubao,
                                NoteRepository noteRepository) {
        this.chroma = chroma;
        this.doubao = doubao;
        this.noteRepository = noteRepository;
    }

    // ==================== 回流开关 ====================

    @Override
    public IndexResult index(String noteId) {
        // 未软删才可回流（findById 已按 soft_delete=0 过滤）
        Note note = noteRepository.findById(noteId);
        List<String> contents = chunkNote(note.getContent());
        try {
            // 幂等：先清旧向量（重开开关时覆盖），避免断点残留
            chroma.deleteByWhere(NOTES_COLLECTION, whereByNote(noteId));
            List<List<Float>> vectors = doubao.embed(contents);
            upsertChunks(note, contents, vectors);
            String version = NoteRepository.nowIso();
            noteRepository.updateIndexState(noteId, 1, "INDEXED", version);
            log.info("note indexed: id={}, chunks={}, version={}", noteId, contents.size(), version);
            return new IndexResult(noteId, true, "INDEXED", contents.size());
        } catch (BusinessException e) {
            // 失败弹回（需求 §3.5）：INDEX_FAILED + is_indexed 回弹 0
            noteRepository.updateIndexState(noteId, 0, "INDEX_FAILED", null);
            log.error("note index failed: id={}, errCode={}, msg={}", noteId, e.getCode(), e.getMessage());
            throw asIndexFailed(e);
        }
    }

    @Override
    public void unindex(String noteId) {
        // 已删/不存在 → 50001（与回流开关操作未软删笔记一致）
        noteRepository.findById(noteId);
        chroma.deleteByWhere(NOTES_COLLECTION, whereByNote(noteId));
        noteRepository.updateIndexState(noteId, 0, "NOT_INDEXED", null);
        log.info("note unindexed: id={}", noteId);
    }

    // ==================== 编辑 / 软删 / 恢复 / 永久删除 同步 ====================

    @Override
    public void syncAfterEdit(Note before, String title, String content) {
        if (before.getIsIndexed() == null || before.getIsIndexed() != 1) {
            return; // 非回流笔记编辑不向量化
        }
        List<String> chunks = chunkNote(content);
        try {
            // 三步事务（Chroma 侧）：删旧 → 重嵌 → 写新；任一步失败抛 50002 由调用方回滚 DB
            chroma.deleteByWhere(NOTES_COLLECTION, whereByNote(before.getNoteId()));
            List<List<Float>> vectors = doubao.embed(chunks);
            upsertChunks(before, title, chunks, vectors);
            log.info("note re-indexed after edit: id={}, chunks={}", before.getNoteId(), chunks.size());
        } catch (BusinessException e) {
            log.error("note re-index failed after edit: id={}, errCode={}", before.getNoteId(), e.getCode());
            throw new BusinessException(ErrorCode.NOTE_INDEX_ROLLBACK);
        }
    }

    @Override
    public void syncAfterDelete(Note before) {
        if (before.getIsIndexed() == null || before.getIsIndexed() != 1) {
            return;
        }
        try {
            chroma.deleteByWhere(NOTES_COLLECTION, whereByNote(before.getNoteId()));
        } catch (BusinessException e) {
            log.error("note vector delete failed on soft-delete: id={}, errCode={}",
                    before.getNoteId(), e.getCode());
            throw new BusinessException(ErrorCode.NOTE_INDEX_ROLLBACK);
        }
        // is_indexed 置 0；last_index_version 保留 → 恢复时凭此判断"曾回流"
        noteRepository.updateIndexState(before.getNoteId(), 0, "NOT_INDEXED", null);
        log.info("note vectors removed on soft-delete: id={}", before.getNoteId());
    }

    @Override
    public void reindexAfterRestore(Note restored) {
        if (restored.getLastIndexVersion() == null || restored.getLastIndexVersion().isBlank()) {
            return; // 未回流过，恢复后保持未回流
        }
        try {
            List<String> chunks = chunkNote(restored.getContent());
            chroma.deleteByWhere(NOTES_COLLECTION, whereByNote(restored.getNoteId()));
            List<List<Float>> vectors = doubao.embed(chunks);
            upsertChunks(restored, chunks, vectors);
            noteRepository.updateIndexState(restored.getNoteId(), 1, "INDEXED", NoteRepository.nowIso());
            log.info("note re-indexed after restore: id={}, chunks={}", restored.getNoteId(), chunks.size());
        } catch (BusinessException e) {
            // 不影响恢复本身：置 INDEX_FAILED，前端可看到状态并手动重试
            noteRepository.updateIndexState(restored.getNoteId(), 0, "INDEX_FAILED", null);
            log.error("note re-index failed after restore: id={}, errCode={}",
                    restored.getNoteId(), e.getCode());
        }
    }

    @Override
    public void syncAfterPurge(Note before) {
        if (before.getIsIndexed() == null || before.getIsIndexed() != 1) {
            return;
        }
        // 失败抛 70001（CHROMA_DOWN）阻断 purge：避免软删过的向量成孤儿
        chroma.deleteByWhere(NOTES_COLLECTION, whereByNote(before.getNoteId()));
        log.info("note vectors removed before purge: id={}", before.getNoteId());
    }

    // ==================== 切片 ====================

    /**
     * 笔记切片：≤800 字单片；&gt;800 字按段落聚合（句号/换行断段），
     * 累计超阈值 flush 前片；单段超长硬切；片数上限 20。
     *
     * @return 切片内容列表（顺序即入库顺序）
     */
    static List<String> chunkNote(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        if (countWords(content) <= CHUNK_THRESHOLD) {
            return List.of(content.trim());
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : splitParagraphs(content)) {
            if (p.isEmpty()) {
                continue;
            }
            if (buf.length() > 0 && countWords(buf.toString()) + countWords(p) > CHUNK_THRESHOLD) {
                flush(chunks, buf);
                buf = new StringBuilder();
            }
            if (countWords(buf.toString()) + countWords(p) > CHUNK_THRESHOLD) {
                // 单段超长：按阈值硬切（保持语义完整优先于字数，仅对无法再聚合的单段执行）
                int pos = 0;
                while (pos < p.length()) {
                    int end = Math.min(pos + CHUNK_THRESHOLD, p.length());
                    String piece = p.substring(pos, end);
                    if (buf.length() > 0) {
                        flush(chunks, buf);
                        buf = new StringBuilder();
                    }
                    chunks.add(piece);
                    pos = end;
                }
            } else {
                buf.append(p);
            }
        }
        if (buf.length() > 0) {
            flush(chunks, buf);
        }
        // 片数上限 20（≈16000 字）：超限拒绝回流，提示精简
        if (chunks.size() > MAX_CHUNKS) {
            throw new BusinessException(ErrorCode.NOTE_INDEX_FAILED,
                    "笔记超过 " + MAX_CHUNKS + " 片切片上限（约16000字），请精简内容后重试");
        }
        return chunks;
    }

    private static void flush(List<String> chunks, StringBuilder buf) {
        String text = buf.toString().trim();
        if (!text.isEmpty()) {
            chunks.add(text);
        }
    }

    /** 按句号/感叹号/问号/换行断段（与 RagChunker 同启发式，笔记场景无章节信息） */
    private static List<String> splitParagraphs(String text) {
        String[] parts = text.split("(?<=[。！？!?\\n])");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    /** 去空白字数（与 NoteServiceImpl 口径一致：中文按字计） */
    private static int countWords(String s) {
        return s == null ? 0 : s.replaceAll("\\s", "").length();
    }

    // ==================== 入库 ====================

    /** 向量写库：id=noteId_序号，metadata 带 note_id（删除/恢复按 note_id 批量操作） */
    private void upsertChunks(Note note, List<String> contents, List<List<Float>> vectors) {
        upsertChunks(note, note.getTitle(), contents, vectors);
    }

    private void upsertChunks(Note note, String title, List<String> contents,
                              List<List<Float>> vectors) {
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();
        for (int i = 0; i < contents.size(); i++) {
            ids.add(note.getNoteId() + "_" + String.format("%02d", i + 1));
            Map<String, Object> meta = new HashMap<>();
            meta.put("note_id", note.getNoteId());
            meta.put("title", title == null ? "" : title);
            meta.put("source_type", note.getSourceType() == null ? "" : note.getSourceType());
            meta.put("position", i + 1);
            meta.put("chunk_count", contents.size());
            metadatas.add(meta);
        }
        chroma.upsert(NOTES_COLLECTION, ids, vectors, contents, metadatas);
    }

    /** 按 note_id 删除全部向量的 where 条件 */
    private static Map<String, Object> whereByNote(String noteId) {
        Map<String, Object> where = new HashMap<>();
        where.put("note_id", noteId);
        return where;
    }

    /** 失败转译：外部依赖错误（Chroma/豆包/超时）原样抛，其余统一 50003（开关弹回） */
    private static BusinessException asIndexFailed(BusinessException e) {
        int code = e.getCode();
        if (code == ErrorCode.CHROMA_DOWN.getCode()
                || code == ErrorCode.DIM_MISMATCH.getCode()
                || code == ErrorCode.DOUBAO_DOWN.getCode()
                || code == ErrorCode.EMBED_TIMEOUT.getCode()) {
            return e;
        }
        return new BusinessException(ErrorCode.NOTE_INDEX_FAILED);
    }
}
