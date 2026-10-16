package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import com.readingnotes.repository.NoteLinkRepository;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.service.NoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 笔记服务实现（F4 第一阶段：CRUD）
 *
 * <p>职责：生成主键/时间戳/版本号、计算字数、校验参数、执行乐观锁编辑，
 * 并把"更新行数=0"细分为"版本冲突(50004)"与"笔记不存在(50001)"。</p>
 */
@Slf4j
@Service
public class NoteServiceImpl implements NoteService {

    private final NoteRepository noteRepository;
    private final NoteLinkRepository noteLinkRepository;

    public NoteServiceImpl(NoteRepository noteRepository, NoteLinkRepository noteLinkRepository) {
        this.noteRepository = noteRepository;
        this.noteLinkRepository = noteLinkRepository;
    }

    // ==================== 新建 ====================

    @Override
    public Note create(Note note) {
        // 参数校验：正文必填（表结构 NOT NULL，空正文无意义）
        if (note.getContent() == null || note.getContent().isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "笔记正文不能为空");
        }
        // 来源类型缺省 MANUAL（手动笔记）；AI 来源由阶段 5 回流接口写入
        String sourceType = (note.getSourceType() == null || note.getSourceType().isBlank())
                ? "MANUAL" : note.getSourceType();

        String now = NoteRepository.nowIso();
        Note n = new Note();
        // 主键：note_ + 时间戳毫秒 + 4 位随机后缀（唯一且可读，文档示例 note_2026xxx）
        n.setNoteId("note_" + System.currentTimeMillis() + "_"
                + (1000 + (int) (Math.random() * 9000)));
        n.setTitle(note.getTitle());
        n.setContent(note.getContent());
        n.setSourceType(sourceType);
        n.setSourceBookId(note.getSourceBookId());
        n.setSourcePage(note.getSourcePage());
        n.setSourceChunkId(note.getSourceChunkId());
        n.setSourceAiVersionId(note.getSourceAiVersionId());
        n.setBookOwnerId(note.getBookOwnerId());
        n.setIsIndexed(0);                    // 手动笔记默认不进向量库
        n.setIndexStatus("NOT_INDEXED");      // 回流状态：未回流
        n.setLastIndexVersion(null);
        n.setWordCount(countWords(note.getContent()));
        n.setEditorVersion(1);                // 初始版本
        n.setSoftDelete(0);
        n.setDeletedAt(null);
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        noteRepository.insert(n);
        log.info("note created: id={}, sourceType={}, words={}", n.getNoteId(), sourceType, n.getWordCount());
        return n;
    }

    // ==================== 列表 / 详情 ====================

    @Override
    public List<Note> list(String sourceBookId, String sourceType, String keyword,
                           String tagName, int page, int pageSize) {
        // 分页参数兜底：页码 ≥1，页大小 1~100（与 BookServiceImpl 一致）
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        return noteRepository.list(sourceBookId, sourceType, keyword, tagName, safePage, safeSize);
    }

    @Override
    public long count(String sourceBookId, String sourceType, String keyword, String tagName) {
        return noteRepository.count(sourceBookId, sourceType, keyword, tagName);
    }

    @Override
    public Note detail(String noteId) {
        return noteRepository.findById(noteId);
    }

    // ==================== 编辑（乐观锁） ====================

    @Override
    public Note update(String noteId, String title, String content, int expectedVersion) {
        // 参数校验：正文必填（编辑也走内容更新，标题可空）
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "笔记正文不能为空");
        }
        int updated = noteRepository.updateContent(
                noteId, title, content, countWords(content), expectedVersion);
        if (updated == 0) {
            // 0 行更新：区分"版本冲突"与"笔记不存在"
            if (noteRepository.exists(noteId)) {
                throw new BusinessException(ErrorCode.NOTE_VERSION_CONFLICT);
            }
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        // 返回更新后的最新数据（含 editor_version+1）
        return noteRepository.findById(noteId);
    }

    // ==================== 软删 / 恢复 / 永久删除 ====================

    @Override
    public void delete(String noteId) {
        int updated = noteRepository.softDelete(noteId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        log.info("note soft-deleted: id={}", noteId);
    }

    @Override
    public void restore(String noteId) {
        int updated = noteRepository.restore(noteId);
        if (updated == 0 && !noteRepository.exists(noteId)) {
            // 0 行且记录不存在 → 笔记不存在
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        // 0 行但记录存在 = 本来就没删 → 幂等成功
        log.info("note restored: id={}", noteId);
    }

    @Override
    @Transactional
    public void purge(String noteId) {
        // 阶段 3 联动：清理该笔记相关的双向链接（source 侧删除 + target 侧 target_jumpable=0）
        noteLinkRepository.purgeNoteLinks(noteId);
        int deleted = noteRepository.purge(noteId);
        if (deleted == 0) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        log.info("note purged: id={}", noteId);
    }

    // ==================== 工具 ====================

    /** 字数统计：去掉全部空白后的字符数（中文按字计，与文档示例口径一致） */
    private static int countWords(String content) {
        return content.replaceAll("\\s", "").length();
    }
}
