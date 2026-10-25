package com.readingnotes.service;

import java.util.Map;

/**
 * 双向链接服务（F4 第三阶段）——接口定义，实现见 NoteLinkServiceImpl
 *
 * <p>覆盖 API 文档 §9.10（查询/创建）。查询返回 outgoing + incoming 两个视角，
 * 实现"双向"语义；创建幂等（同对笔记不重复插）。</p>
 */
public interface NoteLinkService {

    /**
     * 查询笔记的双向链接
     *
     * @return data = {outgoing: [...], incoming: [...]}，
     *         每条含 link_id/target_note_id/target_title/target_jumpable（outgoing）
     *         或 link_id/source_note_id/source_title（incoming）
     */
    Map<String, Object> list(String noteId);

    /**
     * 创建链接（POST /notes/{note_id}/links）
     *
     * @param sourceNoteId 来源笔记（路径参数，必须存在且未软删）
     * @param targetNoteId 目标笔记（必须存在且未软删）
     * @param context      链接上下文（可空）
     * @throws com.readingnotes.common.BusinessException 笔记不存在抛 50001
     */
    void create(String sourceNoteId, String targetNoteId, String context);
}
