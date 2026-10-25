package com.readingnotes.service;

import com.readingnotes.entity.Note;

/**
 * 知识回流服务（API 文档 §9.8 / 需求 §3.5 知识回流）
 *
 * <p>职责：笔记与向量库（Chroma notes_all 集合）的双向同步。
 * <ul>
 *   <li>回流开关：开启=切片→Embedding→入库，关闭=删向量（§9.8）</li>
 *   <li>长笔记切片：>800 字按段切，单笔记上限 20 片，每个 chunk 带 note_id 元数据</li>
 *   <li>编辑同步：删旧向量→重嵌→写新三步事务（§7.3，失败由调用方回滚 DB）</li>
 *   <li>软删/恢复/永久删除：按 note_id 批量操作向量（§9.5/9.6/9.7）</li>
 * </ul>
 */
public interface NoteIndexService {

    /** 回流开关结果（§9.8 响应） */
    record IndexResult(String noteId, boolean isIndexed, String indexStatus, int indexedChunkCount) {
    }

    /**
     * 开启回流（enable=true）：切片→Embedding→notes_all.upsert。
     * 成功置 INDEXED；任一步失败置 INDEX_FAILED 且 is_indexed 回弹 0，抛 50003（开关弹回）。
     *
     * @return 回流结果（含切片数）
     */
    IndexResult index(String noteId);

    /**
     * 关闭回流（enable=false）：删除该笔记全部向量，is_indexed 置 0。
     */
    void unindex(String noteId);

    /**
     * 编辑同步（三步事务，仅 Chroma 侧）：删旧向量 → 重新切片+Embedding → upsert。
     * 任一步失败抛 50002（NOTE_INDEX_ROLLBACK），由调用方保证 DB 内容不提交。
     * 幂等：仅在 before.isIndexed==1 时执行（非回流笔记编辑不向量化）。
     */
    void syncAfterEdit(Note before, String title, String content);

    /**
     * 软删同步（§9.5）：删除该笔记全部向量。is_indexed 置 0，
     * last_index_version 保留（恢复时凭此判断曾回流）。失败抛 50002 由调用方回滚软删。
     */
    void syncAfterDelete(Note before);

    /**
     * 恢复同步（§9.6 / §7.3.4）：曾回流（last_index_version 非空）的笔记重新 Embedding 入库。
     * 成功置 INDEXED；失败置 INDEX_FAILED（不影响恢复本身，可手动重试）。
     */
    void reindexAfterRestore(Note restored);

    /**
     * 永久删除同步（§9.7）：删除该笔记全部向量。失败抛 70001 阻断 purge（避免孤儿向量）。
     */
    void syncAfterPurge(Note before);
}
