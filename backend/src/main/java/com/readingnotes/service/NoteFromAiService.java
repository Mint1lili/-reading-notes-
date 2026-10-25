package com.readingnotes.service;

import com.readingnotes.entity.Note;

/**
 * AI 产物一键存为笔记（API 文档 §9.9）
 *
 * <p>把 B 的 F3 模块产出的 AI 内容（摘要 / 问答 / 导图）沉淀为笔记：
 * 从 {@code ai_versions} 取正式产物，或从 {@code chat_messages} 取一轮问答对，
 * 写入 notes 并按 source_type 自动打类型标签。</p>
 */
public interface NoteFromAiService {

    /**
     * @param sourceType          AI_SUMMARY / AI_QA / AI_MINDMAP
     * @param sourceAiVersionId   AI 产物版本（来自 ai_versions），与 sessionId 二选一
     * @param sessionId           多轮会话（来自 chat_messages），与 sourceAiVersionId 二选一
     * @param title               笔记标题，可空（空则自动生成）
     * @param autoTag             是否按 source_type 自动打「AI摘要 / AI问答 / AI导图」标签
     * @return 新建的笔记实体（含生成的 note_id / 版本号）
     */
    Note createFromAi(String sourceType, String sourceAiVersionId, String sessionId,
                      String title, boolean autoTag);
}
