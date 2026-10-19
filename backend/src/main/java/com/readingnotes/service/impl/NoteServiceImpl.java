package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import com.readingnotes.repository.NoteLinkRepository;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.service.NoteIndexService;
import com.readingnotes.service.NoteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 笔记服务实现（F4：CRUD + 阶段 5 知识回流联动）
 *
 * <p>职责：生成主键/时间戳/版本号、计算字数、校验参数、执行乐观锁编辑，
 * 并把"更新行数=0"细分为"版本冲突(50004)"与"笔记不存在(50001)"。</p>
 *
 * <p>阶段 5 回流联动（需求 §3.5 / API §9.4~9.8）：已回流笔记的
 * 编辑走三步事务（删旧向量→重嵌→写新，失败 50002 回滚）；
 * 软删/恢复/永久删除同步操作 notes_all 向量。</p>
 */
@Slf4j
@Service
public class NoteServiceImpl implements NoteService {

    private final NoteRepository noteRepository;
    private final NoteLinkRepository noteLinkRepository;
    private final NoteIndexService noteIndexService;

    public NoteServiceImpl(NoteRepository noteRepository, NoteLinkRepository noteLinkRepository,
                           NoteIndexService noteIndexService) {
        this.noteRepository = noteRepository;
        this.noteLinkRepository = noteLinkRepository;
        this.noteIndexService = noteIndexService;
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
        // 外键友好校验：source_book_id 指向不存在的书 → MySQL 外键异常会变 500，
        // 这里提前给明确 10001（与 from-ai 链路一致：归属书必须真实存在）
        if (note.getSourceBookId() != null && !note.getSourceBookId().isBlank()
                && !noteRepository.sourceBookExists(note.getSourceBookId())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "来源书不存在，无法绑定");
        }

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
                           String tagName, Boolean indexed, boolean softDeleted,
                           int page, int pageSize) {
        // 分页参数兜底：页码 ≥1，页大小 1~100（与 BookServiceImpl 一致）
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        return noteRepository.list(sourceBookId, sourceType, keyword, tagName,
                indexed, softDeleted, safePage, safeSize);
    }

    @Override
    public long count(String sourceBookId, String sourceType, String keyword,
                      String tagName, Boolean indexed, boolean softDeleted) {
        return noteRepository.count(sourceBookId, sourceType, keyword, tagName,
                indexed, softDeleted);
    }

    @Override
    public Note detail(String noteId) {
        return noteRepository.findById(noteId);
    }

    // ==================== 编辑（乐观锁 + 回流三步事务） ====================

    /**
     * 编辑笔记：版本不匹配 50004、不存在 50001；回流笔记走三步事务。
     *
     * <p>三步事务顺序（§7.3：内容绝不先提交）：先查当前状态 → 若已回流，
     * Chroma 删旧向量→重嵌→写新（任一步失败抛 50002，DB 尚未更新，天然回滚）
     * → 再落库新内容并 +1 版本 → 更新回流状态为 INDEXED。</p>
     */
    @Override
    @Transactional
    public Note update(String noteId, String title, String content, int expectedVersion) {
        // 参数校验：正文必填（编辑也走内容更新，标题可空）
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "笔记正文不能为空");
        }
        Note before = noteRepository.findById(noteId);
        // 先查拦截：repo 查不到（不存在或已软删）返回 null，不能带着 null 走向量同步
        if (before == null) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        // 回流笔记：Chroma 三步事务（失败抛 50002，DB 未动）
        noteIndexService.syncAfterEdit(before, title, content);
        int updated = noteRepository.updateContent(
                noteId, title, content, countWords(content), expectedVersion);
        if (updated == 0) {
            // 0 行更新：区分"版本冲突"与"笔记不存在"
            if (noteRepository.exists(noteId)) {
                throw new BusinessException(ErrorCode.NOTE_VERSION_CONFLICT);
            }
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        // 回流笔记保持回流态：向量已按新内容重建，状态置 INDEXED + 新版本标记
        if (before.getIsIndexed() != null && before.getIsIndexed() == 1) {
            noteRepository.updateIndexState(noteId, 1, "INDEXED", NoteRepository.nowIso());
        }
        // 返回更新后的最新数据（含 editor_version+1）
        return noteRepository.findById(noteId);
    }

    // ==================== 软删 / 恢复 / 永久删除 ====================

    /**
     * 软删除：先查（不存在 50001）→ 若已回流先删 notes_all 向量（失败 50002 回滚，
     * 笔记保持未删）→ 置 soft_delete=1。保证软删后向量必清、无孤儿。
     */
    @Override
    @Transactional
    public void delete(String noteId) {
        Note before = noteRepository.findById(noteId);
        // 先查拦截：不存在或已软删返回 null，避免 null 传入向量同步
        if (before == null) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        noteIndexService.syncAfterDelete(before);
        int updated = noteRepository.softDelete(noteId);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        log.info("note soft-deleted: id={}", noteId);
    }

    /**
     * 恢复：不存在 50001；本来未删幂等成功。曾回流（last_index_version 非空）的笔记
     * 自动重新 Embedding 入库（§7.3.4），重建失败置 INDEX_FAILED 不影响恢复。
     */
    @Override
    public void restore(String noteId) {
        Note raw = noteRepository.findRawById(noteId);
        if (raw == null) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        if (raw.getSoftDelete() == null || raw.getSoftDelete() != 1) {
            // 0 行但记录存在 = 本来就没删 → 幂等成功
            log.info("note restored (already active): id={}", noteId);
            return;
        }
        noteRepository.restore(noteId);
        // 恢复后重新入库（内部吞掉失败，置 INDEX_FAILED 可手动重试）
        noteIndexService.reindexAfterRestore(raw);
        log.info("note restored: id={}", noteId);
    }

    /**
     * 永久删除：不存在 50001；若已回流先删向量（失败 70001 阻断，避免孤儿向量）
     * → 清理双向链接 → 硬删记录。
     */
    @Override
    @Transactional
    public void purge(String noteId) {
        Note raw = noteRepository.findRawById(noteId);
        if (raw == null) {
            throw new BusinessException(ErrorCode.NOTE_NOT_FOUND);
        }
        noteIndexService.syncAfterPurge(raw);
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
