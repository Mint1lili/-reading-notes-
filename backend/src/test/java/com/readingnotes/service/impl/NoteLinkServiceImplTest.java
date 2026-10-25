package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import com.readingnotes.entity.NoteLink;
import com.readingnotes.repository.NoteLinkRepository;
import com.readingnotes.repository.NoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 双向链接服务单元测试（第三阶段）
 *
 * <p>Mockito mock NoteLinkRepository/NoteRepository，测 Service 层规则：
 * 查询校验笔记存在、出/入链组装（目标被永久删除时标题兜底 + jumpable=false）、
 * 创建校验两端笔记 + 同对幂等。</p>
 */
@ExtendWith(MockitoExtension.class)
class NoteLinkServiceImplTest {

    @Mock
    private NoteLinkRepository noteLinkRepository;

    @Mock
    private NoteRepository noteRepository;

    private NoteLinkServiceImpl noteLinkService;

    @BeforeEach
    void setUp() {
        noteLinkService = new NoteLinkServiceImpl(noteLinkRepository, noteRepository);
    }

    // ==================== 查询 ====================

    @Test
    @DisplayName("查询：当前笔记不存在抛 50001")
    void list_noteNotFound() {
        when(noteRepository.findById("note_x"))
                .thenThrow(new BusinessException(ErrorCode.NOTE_NOT_FOUND));

        assertThatThrownBy(() -> noteLinkService.list("note_x"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("查询：出链目标存在时返回真实标题和 jumpable=true")
    void list_outgoing_targetAlive() {
        when(noteRepository.findById("note_a")).thenReturn(new Note());
        NoteLink l = new NoteLink();
        l.setLinkId("link_1");
        l.setSourceNoteId("note_a");
        l.setTargetNoteId("note_b");
        l.setTargetTitle("另一篇笔记");
        l.setTargetJumpable(1);
        when(noteLinkRepository.findOutgoing("note_a")).thenReturn(List.of(l));
        when(noteLinkRepository.findIncoming("note_a")).thenReturn(List.of());

        Map<String, Object> data = noteLinkService.list("note_a");
        List<Map<String, Object>> outgoing = (List<Map<String, Object>>) data.get("outgoing");

        assertThat(outgoing).hasSize(1);
        assertThat(outgoing.get(0).get("target_title")).isEqualTo("另一篇笔记");
        assertThat(outgoing.get(0).get("target_jumpable")).isEqualTo(true);
        assertThat(data.get("incoming")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("查询：目标被永久删除时标题兜底且 jumpable=false")
    void list_outgoing_targetDeleted() {
        when(noteRepository.findById("note_a")).thenReturn(new Note());
        NoteLink l = new NoteLink();
        l.setLinkId("link_1");
        l.setSourceNoteId("note_a");
        l.setTargetNoteId("note_gone");
        l.setTargetTitle(null);            // join 不到目标标题 = 目标已删/软删
        l.setTargetJumpable(1);            // 库里仍为 1，但展示强制 false
        when(noteLinkRepository.findOutgoing("note_a")).thenReturn(List.of(l));
        when(noteLinkRepository.findIncoming("note_a")).thenReturn(List.of());

        Map<String, Object> data = noteLinkService.list("note_a");
        List<Map<String, Object>> outgoing = (List<Map<String, Object>>) data.get("outgoing");

        assertThat(outgoing.get(0).get("target_title")).isEqualTo("已删除笔记（链接失效）");
        assertThat(outgoing.get(0).get("target_jumpable")).isEqualTo(false);
    }

    @Test
    @DisplayName("查询：入链返回来源笔记标题")
    void list_incoming() {
        when(noteRepository.findById("note_b")).thenReturn(new Note());
        when(noteLinkRepository.findOutgoing("note_b")).thenReturn(List.of());
        NoteLink l = new NoteLink();
        l.setLinkId("link_2");
        l.setSourceNoteId("note_a");
        l.setTargetNoteId("note_b");
        l.setSourceTitle("第一篇笔记");
        when(noteLinkRepository.findIncoming("note_b")).thenReturn(List.of(l));

        Map<String, Object> data = noteLinkService.list("note_b");
        List<Map<String, Object>> incoming = (List<Map<String, Object>>) data.get("incoming");

        assertThat(incoming).hasSize(1);
        assertThat(incoming.get(0).get("source_note_id")).isEqualTo("note_a");
        assertThat(incoming.get(0).get("source_title")).isEqualTo("第一篇笔记");
    }

    // ==================== 创建 ====================

    @Test
    @DisplayName("创建成功：两端笔记存在时插入链接")
    void create_success() {
        when(noteRepository.findById("note_a")).thenReturn(new Note());
        when(noteRepository.findById("note_b")).thenReturn(new Note());
        when(noteLinkRepository.exists("note_a", "note_b")).thenReturn(false);

        noteLinkService.create("note_a", "note_b", "参见习惯回路部分");

        verify(noteLinkRepository).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("创建幂等：同对链接已存在时不重复插入")
    void create_idempotent() {
        when(noteRepository.findById("note_a")).thenReturn(new Note());
        when(noteRepository.findById("note_b")).thenReturn(new Note());
        when(noteLinkRepository.exists("note_a", "note_b")).thenReturn(true);

        noteLinkService.create("note_a", "note_b", "again");

        verify(noteLinkRepository, never()).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("创建失败：目标笔记不存在抛 50001")
    void create_targetNotFound() {
        when(noteRepository.findById("note_a")).thenReturn(new Note());
        when(noteRepository.findById("note_x"))
                .thenThrow(new BusinessException(ErrorCode.NOTE_NOT_FOUND));

        assertThatThrownBy(() -> noteLinkService.create("note_a", "note_x", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("创建失败：target_note_id 为空抛 10001")
    void create_blankTarget() {
        when(noteRepository.findById("note_a")).thenReturn(new Note());

        assertThatThrownBy(() -> noteLinkService.create("note_a", "  ", null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(noteLinkRepository, never()).insert(org.mockito.ArgumentMatchers.any());
    }
}
