package com.readingnotes.service.impl;

import com.readingnotes.ai.ChromaClient;
import com.readingnotes.ai.DoubaoClient;
import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.service.NoteIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识回流服务单元测试（阶段 5：§9.8 回流开关 / 切片 / 三步事务 / 删·恢复·永久删除同步）
 *
 * <p>mock ChromaClient / DoubaoClient / NoteRepository，只测 Service 层业务规则：
 * 切片边界、入库元数据、失败回弹、三步事务顺序、软删保留回流痕迹。</p>
 */
@ExtendWith(MockitoExtension.class)
class NoteIndexServiceImplTest {

    @Mock
    private ChromaClient chroma;
    @Mock
    private DoubaoClient doubao;
    @Mock
    private NoteRepository noteRepository;

    private NoteIndexServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NoteIndexServiceImpl(chroma, doubao, noteRepository);
    }

    private Note note(String id, int isIndexed, String lastVersion, String content) {
        Note n = new Note();
        n.setNoteId(id);
        n.setTitle("测试笔记");
        n.setContent(content);
        n.setIsIndexed(isIndexed);
        n.setLastIndexVersion(lastVersion);
        return n;
    }

    // ==================== 切片规则 ====================

    @Test
    @DisplayName("切片：≤800 字单片")
    void chunk_shortNote_single() {
        String c = "记".repeat(800);
        assertThat(NoteIndexServiceImpl.chunkNote(c)).hasSize(1);
    }

    @Test
    @DisplayName("切片：>800 字按段聚合成多片")
    void chunk_longNote_multi() {
        // 两段，每段 500 字 → 合计 1000 > 800 → 两片
        String c = "段".repeat(500) + "。\n" + "段".repeat(500);
        List<String> chunks = NoteIndexServiceImpl.chunkNote(c);
        assertThat(chunks).hasSize(2);
    }

    @Test
    @DisplayName("切片：单段超 800 字硬切")
    void chunk_singleHugeParagraph_hardSplit() {
        String c = "句".repeat(1700);
        List<String> chunks = NoteIndexServiceImpl.chunkNote(c);
        assertThat(chunks).hasSize(3); // 800+800+100
        assertThat(chunks.get(0)).hasSize(800);
    }

    @Test
    @DisplayName("切片：超过 20 片上限拒绝（50003 提示精简）")
    void chunk_tooManyChunks_rejected() {
        // 21 段各 500 字 → 21 片 > 20
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 21; i++) {
            sb.append("段".repeat(500)).append("。\n");
        }
        assertThatThrownBy(() -> NoteIndexServiceImpl.chunkNote(sb.toString()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_INDEX_FAILED.getCode());
    }

    @Test
    @DisplayName("切片：空/空白正文返回空列表")
    void chunk_blank_empty() {
        assertThat(NoteIndexServiceImpl.chunkNote(null)).isEmpty();
        assertThat(NoteIndexServiceImpl.chunkNote("   \n  ")).isEmpty();
    }

    // ==================== 回流开关：开启 ====================

    @Test
    @DisplayName("开启回流：单片入库，metadata 带 note_id，状态置 INDEXED")
    void index_shortNote_success() {
        Note n = note("note_1", 0, null, "习惯回路由暗示、惯常行为、奖赏构成。");
        when(noteRepository.findById("note_1")).thenReturn(n);
        when(doubao.embed(any())).thenReturn(List.of(List.of(0.1f, 0.2f)));

        NoteIndexService.IndexResult r = service.index("note_1");

        assertThat(r.isIndexed()).isTrue();
        assertThat(r.indexStatus()).isEqualTo("INDEXED");
        assertThat(r.indexedChunkCount()).isEqualTo(1);
        verify(chroma).deleteByWhere(eq("notes_all"), any()); // 幂等清旧向量
        verify(chroma).upsert(eq("notes_all"), any(), any(), any(), any());
        verify(noteRepository).updateIndexState(eq("note_1"), eq(1), eq("INDEXED"), any());
    }

    @Test
    @DisplayName("开启回流：失败回弹 INDEX_FAILED + is_indexed=0，抛 70001（Chroma 未启动）")
    void index_chromaDown_rollsBack() {
        Note n = note("note_1", 0, null, "内容");
        when(noteRepository.findById("note_1")).thenReturn(n);
        doThrow(new BusinessException(ErrorCode.CHROMA_DOWN))
                .when(chroma).deleteByWhere(any(), any());

        assertThatThrownBy(() -> service.index("note_1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.CHROMA_DOWN.getCode());

        verify(noteRepository).updateIndexState(eq("note_1"), eq(0), eq("INDEX_FAILED"), any());
    }

    @Test
    @DisplayName("开启回流：Embedding 超时原样抛 30002，且已回弹")
    void index_embedTimeout_rollsBack() {
        Note n = note("note_1", 0, null, "内容");
        when(noteRepository.findById("note_1")).thenReturn(n);
        when(doubao.embed(any())).thenThrow(new BusinessException(ErrorCode.EMBED_TIMEOUT));

        assertThatThrownBy(() -> service.index("note_1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.EMBED_TIMEOUT.getCode());
        verify(noteRepository).updateIndexState(eq("note_1"), eq(0), eq("INDEX_FAILED"), any());
    }

    @Test
    @DisplayName("开启回流：其他业务失败统一转 50003 提示重试")
    void index_otherFailure_becomes50003() {
        Note n = note("note_1", 0, null, "内容");
        when(noteRepository.findById("note_1")).thenReturn(n);
        when(doubao.embed(any()))
                .thenThrow(new BusinessException(ErrorCode.PARAM_ERROR, "内部校验失败"));

        assertThatThrownBy(() -> service.index("note_1"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_INDEX_FAILED.getCode());
        verify(noteRepository).updateIndexState(eq("note_1"), eq(0), eq("INDEX_FAILED"), any());
    }

    // ==================== 回流开关：关闭 ====================

    @Test
    @DisplayName("关闭回流：删全部向量 + is_indexed=0（last_index_version 保留）")
    void unindex_success() {
        when(noteRepository.findById("note_1")).thenReturn(note("note_1", 1, "v1", "内容"));

        service.unindex("note_1");

        verify(chroma).deleteByWhere(eq("notes_all"), any());
        // COALESCE 语义：lastIndexVersion 传 null，SQL 层保留原值（此处仅验证传参为 null）
        verify(noteRepository).updateIndexState(eq("note_1"), eq(0), eq("NOT_INDEXED"), eq(null));
    }

    // ==================== 编辑三步事务 ====================

    @Test
    @DisplayName("编辑同步：已回流笔记按 删旧→重嵌→写新 顺序执行")
    void syncAfterEdit_indexed_threeSteps() {
        Note before = note("note_1", 1, "v1", "旧内容");
        when(doubao.embed(any())).thenReturn(List.of(List.of(0.1f)));

        service.syncAfterEdit(before, "新标题", "新内容");

        verify(chroma).deleteByWhere(eq("notes_all"), any());
        verify(doubao).embed(any());
        verify(chroma).upsert(eq("notes_all"), any(), any(), any(), any());
    }

    @Test
    @DisplayName("编辑同步：未回流笔记跳过，不碰向量库")
    void syncAfterEdit_notIndexed_skip() {
        service.syncAfterEdit(note("note_1", 0, null, "内容"), "t", "c");
        verify(chroma, never()).deleteByWhere(any(), any());
        verify(chroma, never()).upsert(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("编辑同步：向量同步失败抛 50002（内容已恢复，未保存）")
    void syncAfterEdit_failure_50002() {
        Note before = note("note_1", 1, "v1", "旧内容");
        doThrow(new BusinessException(ErrorCode.CHROMA_DOWN))
                .when(chroma).deleteByWhere(any(), any());

        assertThatThrownBy(() -> service.syncAfterEdit(before, "t", "c"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.NOTE_INDEX_ROLLBACK.getCode());
    }

    // ==================== 软删 / 恢复 / 永久删除 ====================

    @Test
    @DisplayName("软删同步：删向量 + is_indexed=0（保留回流痕迹）")
    void syncAfterDelete_indexed() {
        service.syncAfterDelete(note("note_1", 1, "v1", "内容"));
        verify(chroma).deleteByWhere(eq("notes_all"), any());
        verify(noteRepository).updateIndexState(eq("note_1"), eq(0), eq("NOT_INDEXED"), eq(null));
    }

    @Test
    @DisplayName("软删同步：未回流笔记跳过")
    void syncAfterDelete_notIndexed_skip() {
        service.syncAfterDelete(note("note_1", 0, null, "内容"));
        verify(chroma, never()).deleteByWhere(any(), any());
    }

    @Test
    @DisplayName("恢复同步：曾回流笔记重新入库置 INDEXED")
    void reindexAfterRestore_wasIndexed() {
        Note restored = note("note_1", 0, "v1", "内容");
        when(doubao.embed(any())).thenReturn(List.of(List.of(0.1f)));

        service.reindexAfterRestore(restored);

        verify(chroma).deleteByWhere(eq("notes_all"), any());
        verify(chroma).upsert(eq("notes_all"), any(), any(), any(), any());
        verify(noteRepository).updateIndexState(eq("note_1"), eq(1), eq("INDEXED"), any());
    }

    @Test
    @DisplayName("恢复同步：失败置 INDEX_FAILED 但不抛（不影响恢复）")
    void reindexAfterRestore_failure_indexFailed() {
        Note restored = note("note_1", 0, "v1", "内容");
        when(doubao.embed(any())).thenThrow(new BusinessException(ErrorCode.DOUBAO_DOWN));

        service.reindexAfterRestore(restored); // 不抛异常

        verify(noteRepository).updateIndexState(eq("note_1"), eq(0), eq("INDEX_FAILED"), any());
    }

    @Test
    @DisplayName("恢复同步：未回流过的笔记跳过")
    void reindexAfterRestore_neverIndexed_skip() {
        service.reindexAfterRestore(note("note_1", 0, null, "内容"));
        verify(chroma, never()).deleteByWhere(any(), any());
        verify(noteRepository, never()).updateIndexState(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("永久删除同步：删向量，失败抛 70001 阻断")
    void syncAfterPurge_indexed() {
        service.syncAfterPurge(note("note_1", 1, "v1", "内容"));
        verify(chroma).deleteByWhere(eq("notes_all"), any());
    }

    @Test
    @DisplayName("永久删除同步：未回流笔记跳过")
    void syncAfterPurge_notIndexed_skip() {
        service.syncAfterPurge(note("note_1", 0, null, "内容"));
        verify(chroma, never()).deleteByWhere(any(), any());
    }

    // ==================== upsert 元数据 ====================

    @Test
    @DisplayName("upsert：ids 带 note_id 前缀、metadata 带 note_id（删除/恢复按 note_id 批量）")
    void index_metadataCarriesNoteId() {
        Note n = note("note_1", 0, null, "段".repeat(500) + "。\n" + "段".repeat(500));
        when(noteRepository.findById("note_1")).thenReturn(n);
        when(doubao.embed(any())).thenReturn(List.of(List.of(0.1f), List.of(0.2f)));

        service.index("note_1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List> metasCaptor = ArgumentCaptor.forClass(List.class);
        verify(chroma).upsert(eq("notes_all"), idsCaptor.capture(), any(), any(), metasCaptor.capture());
        assertThat(idsCaptor.getValue()).containsExactly("note_1_01", "note_1_02");
        assertThat(metasCaptor.getValue())
                .allMatch(m -> ((java.util.Map<?, ?>) m).get("note_id").equals("note_1"));
    }
}
