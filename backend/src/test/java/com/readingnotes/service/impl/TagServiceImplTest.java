package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Tag;
import com.readingnotes.repository.TagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 标签服务单元测试（第二阶段）
 *
 * <p>Mockito mock TagRepository，测 Service 层规则：
 * 显式创建（重名/空名拒绝）、笔记打标自动创建（幂等）、重复绑定静默跳过、列表透传。</p>
 */
@ExtendWith(MockitoExtension.class)
class TagServiceImplTest {

    @Mock
    private TagRepository tagRepository;

    private TagServiceImpl tagService;

    @BeforeEach
    void setUp() {
        tagService = new TagServiceImpl(tagRepository);
    }

    // ==================== 创建（POST /tags） ====================

    @Test
    @DisplayName("创建成功：生成 tag_ 主键并落库")
    void create_success() {
        when(tagRepository.findIdByName("方法论")).thenReturn(null);

        Tag result = tagService.create("方法论", "#E6A23C");

        assertThat(result.getTagId()).startsWith("tag_");
        assertThat(result.getName()).isEqualTo("方法论");
        assertThat(result.getColor()).isEqualTo("#E6A23C");
        assertThat(result.getCreatedAt()).isNotNull();
        verify(tagRepository).insert(any(Tag.class));
    }

    @Test
    @DisplayName("创建失败：标签名已存在抛 10001")
    void create_duplicateName_rejected() {
        when(tagRepository.findIdByName("读书会")).thenReturn("tag_book_club");

        assertThatThrownBy(() -> tagService.create("读书会", "#409EFF"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(tagRepository, never()).insert(any(Tag.class));
    }

    @Test
    @DisplayName("创建失败：标签名为空抛 10001")
    void create_blankName_rejected() {
        assertThatThrownBy(() -> tagService.create("   ", "#409EFF"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PARAM_ERROR.getCode());
        verify(tagRepository, never()).insert(any(Tag.class));
    }

    // ==================== 绑定（笔记创建自动打标） ====================

    @Test
    @DisplayName("绑定：标签不存在时自动创建并绑定")
    void bindTags_autoCreateAndAttach() {
        when(tagRepository.findIdByName(any())).thenReturn(null);

        tagService.bindTags("note_1", List.of("新标签A", "新标签B"));

        // 两个标签都不存在 → 各创建一次
        ArgumentCaptor<Tag> captor = ArgumentCaptor.forClass(Tag.class);
        verify(tagRepository, times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(Tag::getName)
                .containsExactlyInAnyOrder("新标签A", "新标签B");
        // 各绑定一次
        verify(tagRepository, times(2)).attach(eq("note_1"), any());
    }

    @Test
    @DisplayName("绑定：标签已存在时复用，不重复创建")
    void bindTags_reuseExisting() {
        when(tagRepository.findIdByName("读书会")).thenReturn("tag_book_club");
        when(tagRepository.findIdByName("高频考点")).thenReturn("tag_high_freq");

        tagService.bindTags("note_1", List.of("读书会", "高频考点"));

        verify(tagRepository, never()).insert(any(Tag.class));
        verify(tagRepository).attach("note_1", "tag_book_club");
        verify(tagRepository).attach("note_1", "tag_high_freq");
    }

    @Test
    @DisplayName("绑定：空列表或空名直接忽略")
    void bindTags_ignoreEmptyAndBlank() {
        tagService.bindTags("note_1", null);
        tagService.bindTags("note_1", List.of());
        // 注：List.of 不允许 null 元素，这里用 Arrays.asList
        tagService.bindTags("note_1", Arrays.asList("  ", null, ""));

        verify(tagRepository, never()).insert(any(Tag.class));
        verify(tagRepository, never()).attach(any(), any());
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("列表：透传 listWithCount")
    void list_passthrough() {
        Tag t = new Tag();
        t.setTagId("tag_1");
        t.setName("读书会");
        t.setNoteCount(5);
        when(tagRepository.listWithCount()).thenReturn(List.of(t));

        List<Tag> result = tagService.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNoteCount()).isEqualTo(5);
    }
}
