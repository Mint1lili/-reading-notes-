package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import com.readingnotes.repository.AiArtifactRepository;
import com.readingnotes.service.NoteService;
import com.readingnotes.service.TagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 产物一键存为笔记单元测试（API §9.9）
 *
 * <p>Mock AiArtifactRepository / NoteService / TagService，覆盖：
 * version 与 session 两条取数路径、产物类型校验、来源书回填、
 * 自动标题与截断、auto_tag 自动打标、错误分支。不依赖真实数据库。</p>
 */
@ExtendWith(MockitoExtension.class)
class NoteFromAiServiceImplTest {

    @Mock
    private AiArtifactRepository aiRepository;

    @Mock
    private NoteService noteService;

    @Mock
    private TagService tagService;

    private NoteFromAiServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NoteFromAiServiceImpl(aiRepository, noteService, tagService);
    }

    // ==================== 辅助 ====================

    /** 让 noteService.create 捕获 draft 并补 note_id 后返回（模拟落库） */
    private void stubCreateReturnsWithId() {
        when(noteService.create(any(Note.class))).thenAnswer(inv -> {
            Note draft = inv.getArgument(0);
            draft.setNoteId("note_new");
            return draft;
        });
    }

    private AiArtifactRepository.AiVersion version(String artifactType, String targetType,
                                                   String targetId, String content) {
        return new AiArtifactRepository.AiVersion("v1", targetType, targetId,
                artifactType, content, "2026-10-17T10:00:00.000Z");
    }

    private AiArtifactRepository.QaPair qa(String q, String a) {
        return new AiArtifactRepository.QaPair(q, a);
    }

    // ==================== version 路径 ====================

    @Test
    @DisplayName("AI 摘要产物存笔记：回填来源书、自动标题、auto_tag 打「AI摘要」")
    void fromVersion_summary_autoTag() {
        stubCreateReturnsWithId();
        when(aiRepository.findVersion("v1")).thenReturn(
                version("SUMMARY", "book", "book_1", "本书摘要正文"));
        when(aiRepository.findBookTitle("book_1")).thenReturn("习惯的力量");

        Note created = service.createFromAi("AI_SUMMARY", "v1", null, null, true);

        assertThat(created.getNoteId()).isEqualTo("note_new");
        assertThat(created.getContent()).isEqualTo("本书摘要正文");
        assertThat(created.getSourceBookId()).isEqualTo("book_1");
        assertThat(created.getSourceAiVersionId()).isEqualTo("v1");
        assertThat(created.getSourceType()).isEqualTo("AI_SUMMARY");
        assertThat(created.getTitle()).isEqualTo("AI摘要 - 习惯的力量");
        verify(tagService).bindTags(eq("note_new"), eq(List.of("AI摘要")));
    }

    @Test
    @DisplayName("导图产物存笔记：target 非 book 时不回填来源书，标题用默认「AI导图」")
    void fromVersion_mindmap_noBook() {
        stubCreateReturnsWithId();
        when(aiRepository.findVersion("v1")).thenReturn(
                version("MINDMAP", "cross", "scope_x", "导图内容"));

        Note created = service.createFromAi("AI_MINDMAP", "v1", null, null, false);

        assertThat(created.getSourceBookId()).isNull();
        assertThat(created.getTitle()).isEqualTo("AI导图");
        verify(tagService, never()).bindTags(any(), anyList());
    }

    @Test
    @DisplayName("产物类型与 source_type 不匹配抛 10001")
    void fromVersion_typeMismatch_rejected() {
        when(aiRepository.findVersion("v1")).thenReturn(
                version("ANSWER", "book", "book_1", "回答内容"));

        assertThatThrownBy(() -> service.createFromAi("AI_SUMMARY", "v1", null, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("产物版本不存在抛 40003")
    void fromVersion_notFound() {
        when(aiRepository.findVersion("v_missing")).thenReturn(null);

        assertThatThrownBy(() -> service.createFromAi("AI_QA", "v_missing", null, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.VERSION_NOT_FOUND.getCode());
    }

    // ==================== session 路径 ====================

    @Test
    @DisplayName("会话问答对存笔记：问题回答结构化、来源书回填、标题取问题前 30 字")
    void fromSession_qaPair_success() {
        stubCreateReturnsWithId();
        when(aiRepository.findLatestQa("s1")).thenReturn(
                qa("什么是习惯回路？", "暗示、惯常行为、奖赏构成闭环。"));
        when(aiRepository.findSessionBookId("s1")).thenReturn("book_1");

        Note created = service.createFromAi("AI_QA", null, "s1", null, false);

        assertThat(created.getContent()).contains("## 问题", "什么是习惯回路？", "## 回答", "暗示、惯常行为");
        assertThat(created.getSourceBookId()).isEqualTo("book_1");
        assertThat(created.getSourceType()).isEqualTo("AI_QA");
        assertThat(created.getTitle()).isEqualTo("AI问答 - 什么是习惯回路？");
        verify(tagService, never()).bindTags(any(), anyList());
    }

    @Test
    @DisplayName("会话只有提问无回答（AI 流式中）仍可沉淀，回答留空")
    void fromSession_questionOnly() {
        stubCreateReturnsWithId();
        when(aiRepository.findLatestQa("s1")).thenReturn(qa("只有问题", null));

        Note created = service.createFromAi("AI_QA", null, "s1", null, false);

        assertThat(created.getContent()).contains("## 问题", "只有问题", "## 回答");
    }

    @Test
    @DisplayName("会话无可用问答抛 10002")
    void fromSession_noQa_notFound() {
        when(aiRepository.findLatestQa("s_empty")).thenReturn(null);

        assertThatThrownBy(() -> service.createFromAi("AI_QA", null, "s_empty", null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }

    // ==================== 参数校验 ====================

    @Test
    @DisplayName("version 与 session 都不传抛 10001")
    void bothSourcesMissing_rejected() {
        assertThatThrownBy(() -> service.createFromAi("AI_QA", null, null, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    @Test
    @DisplayName("source_type 非法抛 10001")
    void invalidSourceType_rejected() {
        assertThatThrownBy(() -> service.createFromAi("AI_XXX", "v1", null, null, false))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
    }

    // ==================== 标题规则 ====================

    @Test
    @DisplayName("显式 title 覆盖自动标题")
    void explicitTitle_overrides() {
        stubCreateReturnsWithId();
        when(aiRepository.findVersion("v1")).thenReturn(
                version("ANSWER", "book", "book_1", "回答内容"));

        Note created = service.createFromAi("AI_QA", "v1", null, "我的自定义标题", false);

        assertThat(created.getTitle()).isEqualTo("我的自定义标题");
    }

    @Test
    @DisplayName("书名兜底为空时标题用纯前缀")
    void bookTitleMissing_defaultPrefix() {
        stubCreateReturnsWithId();
        when(aiRepository.findVersion("v1")).thenReturn(
                version("SUMMARY", "book", "book_1", "摘要"));
        when(aiRepository.findBookTitle("book_1")).thenReturn(null);

        Note created = service.createFromAi("AI_SUMMARY", "v1", null, null, false);

        assertThat(created.getTitle()).isEqualTo("AI摘要");
    }

    @Test
    @DisplayName("超长问题标题截断 30 字并补省略号")
    void longQuestion_truncated() {
        stubCreateReturnsWithId();
        String longQ = "这是一个非常非常非常非常非常非常非常非常非常长的测试问题标题用来验证截断逻辑是否正确";
        when(aiRepository.findLatestQa("s1")).thenReturn(qa(longQ, "回答"));
        when(aiRepository.findSessionBookId("s1")).thenReturn(null);

        Note created = service.createFromAi("AI_QA", null, "s1", null, false);

        assertThat(created.getTitle()).startsWith("AI问答 - ");
        assertThat(created.getTitle()).endsWith("...");
        // 前缀 + 30 字 + 省略号
        assertThat(created.getTitle().length()).isEqualTo("AI问答 - ".length() + 30 + 3);
    }
}
