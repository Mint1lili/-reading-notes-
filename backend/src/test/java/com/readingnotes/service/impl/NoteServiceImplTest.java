package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import com.readingnotes.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 笔记服务单元测试（第一阶段 CRUD）
 *
 * <p>用 Mockito mock NoteRepository，只测 Service 层业务规则：
 * 参数校验、主键/时间生成、字数统计、乐观锁冲突拆分、软删/恢复/永久删除。
 * 不依赖真实数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class NoteServiceImplTest {

    @Mock
    private NoteRepository noteRepository;

    private NoteServiceImpl noteService;

    @BeforeEach
    void setUp() {
        noteService = new NoteServiceImpl(noteRepository);
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

    // ==================== 编辑（乐观锁） ====================

    @Test
    @DisplayName("编辑成功：版本匹配则更新并返回最新数据")
    void update_success() {
        Note updated = new Note();
        updated.setNoteId("note_1");
        updated.setTitle("新标题");
        updated.setContent("更新后的内容");
        updated.setEditorVersion(2);
        updated.setWordCount(6);
        updated.setUpdatedAt("2026-10-16T00:00:00.000Z");
        when(noteRepository.updateContent(eq("note_1"), eq("新标题"), eq("更新后的内容"),
                anyInt(), eq(1))).thenReturn(1);
        when(noteRepository.findById("note_1")).thenReturn(updated);

        Note result = noteService.update("note_1", "新标题", "更新后的内容", 1);

        assertThat(result.getEditorVersion()).isEqualTo(2);
        assertThat(result.getTitle()).isEqualTo("新标题");
    }

    @Test
    @DisplayName("编辑失败：版本不匹配抛 50004 版本冲突")
    void update_versionConflict() {
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
        when(noteRepository.updateContent(eq("note_x"), eq(null), eq("新内容"),
                anyInt(), eq(1))).thenReturn(0);
        when(noteRepository.exists("note_x")).thenReturn(false);

        assertThatThrownBy(() -> noteService.update("note_x", null, "新内容", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
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
    @DisplayName("软删除成功：调用 softDelete 并返回")
    void delete_success() {
        when(noteRepository.softDelete("note_1")).thenReturn(1);

        noteService.delete("note_1");

        verify(noteRepository).softDelete("note_1");
    }

    @Test
    @DisplayName("软删除失败：不存在抛 50001")
    void delete_notFound() {
        when(noteRepository.softDelete("note_x")).thenReturn(0);

        assertThatThrownBy(() -> noteService.delete("note_x"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("恢复成功：已软删的笔记恢复为正常")
    void restore_success() {
        when(noteRepository.restore("note_1")).thenReturn(1);

        noteService.restore("note_1");

        verify(noteRepository).restore("note_1");
    }

    @Test
    @DisplayName("恢复幂等：未软删的笔记再次恢复视为成功")
    void restore_idempotent_whenAlreadyNormal() {
        when(noteRepository.restore("note_1")).thenReturn(0);
        when(noteRepository.exists("note_1")).thenReturn(true);

        noteService.restore("note_1"); // 不抛异常即通过
    }

    @Test
    @DisplayName("恢复失败：笔记不存在抛 50001")
    void restore_notFound() {
        when(noteRepository.restore("note_x")).thenReturn(0);
        when(noteRepository.exists("note_x")).thenReturn(false);

        assertThatThrownBy(() -> noteService.restore("note_x"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("永久删除成功")
    void purge_success() {
        when(noteRepository.purge("note_1")).thenReturn(1);

        noteService.purge("note_1");

        verify(noteRepository).purge("note_1");
    }

    @Test
    @DisplayName("永久删除失败：不存在抛 50001")
    void purge_notFound() {
        when(noteRepository.purge("note_x")).thenReturn(0);

        assertThatThrownBy(() -> noteService.purge("note_x"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    // ==================== 列表分页参数兜底 ====================

    @Test
    @DisplayName("列表：页码/页大小越界时兜底到 1~100")
    void list_safePaging() {
        // 注：count 是 Controller 调用（total 字段），Service 层 list 不触碰 count
        when(noteRepository.list(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(java.util.List.of());

        noteService.list(null, null, null, null, 0, 999);
        noteService.list(null, null, null, null, -5, 0);

        verify(noteRepository).list(eq(null), eq(null), eq(null), eq(null), eq(1), eq(100));
        verify(noteRepository).list(eq(null), eq(null), eq(null), eq(null), eq(1), eq(1));
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
