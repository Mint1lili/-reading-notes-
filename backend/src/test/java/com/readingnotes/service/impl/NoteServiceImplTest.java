package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import com.readingnotes.repository.NoteLinkRepository;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.service.NoteIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 笔记服务单元测试（CRUD + 阶段 5 知识回流联动）
 *
 * <p>用 Mockito mock NoteRepository / NoteLinkRepository / NoteIndexService，
 * 只测 Service 层业务规则：参数校验、主键/时间生成、字数统计、乐观锁冲突拆分、
 * 软删/恢复/永久删除，以及回流笔记的编辑三步事务与向量同步联动。</p>
 */
@ExtendWith(MockitoExtension.class)
class NoteServiceImplTest {

    @Mock
    private NoteRepository noteRepository;

    @Mock
    private NoteLinkRepository noteLinkRepository;

    @Mock
    private NoteIndexService noteIndexService;

    private NoteServiceImpl noteService;

    @BeforeEach
    void setUp() {
        noteService = new NoteServiceImpl(noteRepository, noteLinkRepository, noteIndexService);
    }

    /** 未回流笔记（is_indexed=0，回流联动应跳过） */
    private Note plainNote(String id, int softDelete) {
        Note n = new Note();
        n.setNoteId(id);
        n.setTitle("标题");
        n.setContent("内容");
        n.setIsIndexed(0);
        n.setIndexStatus("NOT_INDEXED");
        n.setEditorVersion(1);
        n.setSoftDelete(softDelete);
        return n;
    }

    /** 已回流笔记（is_indexed=1，回流联动应执行） */
    private Note indexedNote(String id, int softDelete) {
        Note n = plainNote(id, softDelete);
        n.setIsIndexed(1);
        n.setIndexStatus("INDEXED");
        n.setLastIndexVersion("v1");
        return n;
    }

    // ==================== 新建 ====================

    @Test
    @DisplayName("创建成功：生成 note_ 主键、版本 1、字数统计、默认 MANUAL")
    void create_success() {
        Note input = new Note();
        input.setTitle("测试标题");
        input.setContent("习惯回路 包括  暗示 惯常行为 奖赏");
        // sourceType 不传 → 应默认为 MANUAL

        Note result = noteService.create(input);

        assertThat(result.getNoteId()).startsWith("note_");
        assertThat(result.getSourceType()).isEqualTo("MANUAL");
        assertThat(result.getEditorVersion()).isEqualTo(1);
        assertThat(result.getSoftDelete()).isEqualTo(0);
        assertThat(result.getIsIndexed()).isEqualTo(0);
        assertThat(result.getIndexStatus()).isEqualTo("NOT_INDEXED");
        assertThat(result.getCreatedAt()).isNotNull();
        assertThat(result.getUpdatedAt()).isNotNull();
        // 去空白字数：习惯回路/包括/暗示/惯常行为/奖赏 = 14 字
        assertThat(result.getWordCount()).isEqualTo(14);
        verify(noteRepository).insert(any(Note.class));
    }

    @Test
    @DisplayName("创建失败：正文为空抛 10001")
    void create_blankContent_rejected() {
        Note input = new Note();
        input.setContent("   ");

        assertThatThrownBy(() -> noteService.create(input))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("创建失败：来源书不存在抛 10001（外键友好提示，不落库）")
    void create_sourceBookMissing_rejected() {
        Note input = new Note();
        input.setContent("一条带书归属的笔记");
        input.setSourceBookId("book_not_exist");
        when(noteRepository.sourceBookExists("book_not_exist")).thenReturn(false);

        assertThatThrownBy(() -> noteService.create(input))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(noteRepository, never()).insert(any());
    }

    // ==================== 编辑（乐观锁 + 回流三步事务） ====================

    @Test
    @DisplayName("编辑成功（非回流）：版本匹配则更新并返回最新数据")
    void update_success() {
        Note before = plainNote("note_1", 0);
        Note updated = new Note();
        updated.setNoteId("note_1");
        updated.setTitle("新标题");
        updated.setContent("更新后的内容");
        updated.setEditorVersion(2);
        updated.setWordCount(6);
        updated.setUpdatedAt("2026-10-16T00:00:00.000Z");
        when(noteRepository.findById("note_1")).thenReturn(before);
        when(noteRepository.updateContent(eq("note_1"), eq("新标题"), eq("更新后的内容"),
                anyInt(), eq(1))).thenReturn(1);
        when(noteRepository.findById("note_1")).thenReturn(before, updated);

        Note result = noteService.update("note_1", "新标题", "更新后的内容", 1);

        assertThat(result.getEditorVersion()).isEqualTo(2);
        assertThat(result.getTitle()).isEqualTo("新标题");
        // 非回流笔记：syncAfterEdit 被调用但内部跳过（不落库、不碰向量）
        verify(noteIndexService).syncAfterEdit(any(), any(), any());
        verify(noteRepository, never()).updateIndexState(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("编辑成功（已回流）：先三步向量同步，落库后状态保持 INDEXED")
    void update_indexedNote_syncsVector() {
        Note before = indexedNote("note_1", 0);
        Note updated = new Note();
        updated.setNoteId("note_1");
        updated.setTitle("新标题");
        updated.setContent("更新后的内容");
        updated.setEditorVersion(2);
        updated.setIsIndexed(1);
        updated.setIndexStatus("INDEXED");
        updated.setUpdatedAt("2026-10-16T00:00:00.000Z");
        when(noteRepository.findById("note_1")).thenReturn(before, updated);
        when(noteRepository.updateContent(eq("note_1"), eq("新标题"), eq("更新后的内容"),
                anyInt(), eq(1))).thenReturn(1);

        Note result = noteService.update("note_1", "新标题", "更新后的内容", 1);

        verify(noteIndexService).syncAfterEdit(eq(before), eq("新标题"), eq("更新后的内容"));
        verify(noteRepository).updateIndexState(eq("note_1"), eq(1), eq("INDEXED"), any());
        assertThat(result.getIsIndexed()).isEqualTo(1);
    }

    @Test
    @DisplayName("编辑失败（已回流）：向量同步失败抛 50002 内容未保存")
    void update_indexedNote_vectorSyncFail_50002() {
        Note before = indexedNote("note_1", 0);
        when(noteRepository.findById("note_1")).thenReturn(before);
        doThrow(new BusinessException(ErrorCode.NOTE_INDEX_ROLLBACK))
                .when(noteIndexService).syncAfterEdit(any(), any(), any());

        assertThatThrownBy(() -> noteService.update("note_1", "新标题", "新内容", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_INDEX_ROLLBACK.getCode());
        // DB 未更新（三步事务：Chroma 失败则内容不提交）
        verify(noteRepository, never()).updateContent(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("编辑失败：版本不匹配抛 50004 版本冲突")
    void update_versionConflict() {
        when(noteRepository.findById("note_1")).thenReturn(plainNote("note_1", 0));
        when(noteRepository.updateContent(eq("note_1"), eq("新标题"), eq("新内容"),
                anyInt(), eq(1))).thenReturn(0);
        when(noteRepository.exists("note_1")).thenReturn(true);

        assertThatThrownBy(() -> noteService.update("note_1", "新标题", "新内容", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_VERSION_CONFLICT.getCode());
    }

    @Test
    @DisplayName("编辑失败：笔记不存在抛 50001")
    void update_noteNotFound() {
        when(noteRepository.findById("note_x"))
                .thenThrow(new BusinessException(ErrorCode.NOTE_NOT_FOUND));

        assertThatThrownBy(() -> noteService.update("note_x", null, "新内容", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("编辑失败：repo 返回 null（不存在或已软删）抛 50001，不触向量同步")
    void update_repoReturnsNull_noteNotFound() {
        when(noteRepository.findById("note_x")).thenReturn(null);

        assertThatThrownBy(() -> noteService.update("note_x", "新标题", "新内容", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
        verify(noteIndexService, never()).syncAfterEdit(any(), any(), any());
        verify(noteRepository, never()).updateContent(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("编辑失败：正文为空抛 10001")
    void update_blankContent_rejected() {
        assertThatThrownBy(() -> noteService.update("note_1", "标题", "  ", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    // ==================== 软删 / 恢复 / 永久删除 ====================

    @Test
    @DisplayName("软删除成功（非回流）：先查后删，不触向量库")
    void delete_success() {
        when(noteRepository.findById("note_1")).thenReturn(plainNote("note_1", 0));
        when(noteRepository.softDelete("note_1")).thenReturn(1);

        noteService.delete("note_1");

        verify(noteRepository).softDelete("note_1");
        verify(noteIndexService).syncAfterDelete(any(Note.class)); // 非回流内部跳过
    }

    @Test
    @DisplayName("软删除成功（已回流）：向量同步在软删前执行")
    void delete_indexedNote_syncsVector() {
        when(noteRepository.findById("note_1")).thenReturn(indexedNote("note_1", 0));
        when(noteRepository.softDelete("note_1")).thenReturn(1);

        noteService.delete("note_1");

        verify(noteIndexService).syncAfterDelete(any(Note.class));
        verify(noteRepository).softDelete("note_1");
    }

    @Test
    @DisplayName("软删除失败：不存在抛 50001（先查拦截）")
    void delete_notFound() {
        when(noteRepository.findById("note_x"))
                .thenThrow(new BusinessException(ErrorCode.NOTE_NOT_FOUND));

        assertThatThrownBy(() -> noteService.delete("note_x"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("软删除失败：repo 返回 null（不存在或已软删）抛 50001，不触向量同步")
    void delete_repoReturnsNull_noteNotFound() {
        when(noteRepository.findById("note_x")).thenReturn(null);

        assertThatThrownBy(() -> noteService.delete("note_x"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
        verify(noteIndexService, never()).syncAfterDelete(any());
        verify(noteRepository, never()).softDelete(any());
    }

    @Test
    @DisplayName("软删除失败（已回流）：向量删除失败抛 50002，笔记保持未删")
    void delete_indexedNote_vectorFail_50002() {
        when(noteRepository.findById("note_1")).thenReturn(indexedNote("note_1", 0));
        doThrow(new BusinessException(ErrorCode.NOTE_INDEX_ROLLBACK))
                .when(noteIndexService).syncAfterDelete(any());

        assertThatThrownBy(() -> noteService.delete("note_1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_INDEX_ROLLBACK.getCode());
        verify(noteRepository, never()).softDelete(any());
    }

    @Test
    @DisplayName("恢复成功（未回流过）：软删笔记恢复，不重新入库")
    void restore_success() {
        Note softDeleted = plainNote("note_1", 1);
        when(noteRepository.findRawById("note_1")).thenReturn(softDeleted);
        when(noteRepository.restore("note_1")).thenReturn(1);

        noteService.restore("note_1");

        verify(noteRepository).restore("note_1");
        // 未回流过的笔记：reindexAfterRestore 被调用但内部跳过（lastIndexVersion 为空）
        verify(noteIndexService).reindexAfterRestore(any());
    }

    @Test
    @DisplayName("恢复成功（曾回流）：恢复后自动重新入库")
    void restore_wasIndexed_reindexes() {
        Note softDeleted = indexedNote("note_1", 1);
        when(noteRepository.findRawById("note_1")).thenReturn(softDeleted);
        when(noteRepository.restore("note_1")).thenReturn(1);

        noteService.restore("note_1");

        verify(noteIndexService).reindexAfterRestore(softDeleted);
    }

    @Test
    @DisplayName("恢复幂等：未软删的笔记再次恢复视为成功")
    void restore_idempotent_whenAlreadyNormal() {
        when(noteRepository.findRawById("note_1")).thenReturn(plainNote("note_1", 0));

        noteService.restore("note_1"); // 不抛异常即通过

        verify(noteRepository, never()).restore(any());
    }

    @Test
    @DisplayName("恢复失败：笔记不存在抛 50001")
    void restore_notFound() {
        when(noteRepository.findRawById("note_x")).thenReturn(null);

        assertThatThrownBy(() -> noteService.restore("note_x"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("永久删除成功（非回流）：先查后删，联动清理链接")
    void purge_success() {
        when(noteRepository.findRawById("note_1")).thenReturn(plainNote("note_1", 0));
        when(noteRepository.purge("note_1")).thenReturn(1);

        noteService.purge("note_1");

        verify(noteRepository).purge("note_1");
        // 阶段 3 联动：purge 时先处理双向链接（target 侧置灰）
        verify(noteLinkRepository).purgeNoteLinks("note_1");
        verify(noteIndexService).syncAfterPurge(any(Note.class)); // 非回流内部跳过
    }

    @Test
    @DisplayName("永久删除成功（已回流）：先删向量再 purge")
    void purge_indexedNote_syncsVector() {
        when(noteRepository.findRawById("note_1")).thenReturn(indexedNote("note_1", 0));
        when(noteRepository.purge("note_1")).thenReturn(1);

        noteService.purge("note_1");

        verify(noteIndexService).syncAfterPurge(any(Note.class));
        verify(noteRepository).purge("note_1");
    }

    @Test
    @DisplayName("永久删除失败：不存在抛 50001")
    void purge_notFound() {
        when(noteRepository.findRawById("note_x")).thenReturn(null);

        assertThatThrownBy(() -> noteService.purge("note_x"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    // ==================== 列表分页参数兜底 ====================

    @Test
    @DisplayName("列表：页码/页大小越界时兜底到 1~100")
    void list_safePaging() {
        // 签名：bookId, sourceType, keyword, tagName, indexed, softDeleted, page, pageSize
        when(noteRepository.list(any(), any(), any(), any(), any(), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(java.util.List.of());

        noteService.list(null, null, null, null, null, false, 0, 999);
        noteService.list(null, null, null, null, null, false, -5, 0);

        verify(noteRepository).list(eq(null), eq(null), eq(null), eq(null), eq(null), eq(false), eq(1), eq(100));
        verify(noteRepository).list(eq(null), eq(null), eq(null), eq(null), eq(null), eq(false), eq(1), eq(1));
    }

    @Test
    @DisplayName("详情：透传 repository.findById")
    void detail_passthrough() {
        Note n = new Note();
        n.setNoteId("note_1");
        when(noteRepository.findById("note_1")).thenReturn(n);

        assertThat(noteService.detail("note_1").getNoteId()).isEqualTo("note_1");
    }
}
