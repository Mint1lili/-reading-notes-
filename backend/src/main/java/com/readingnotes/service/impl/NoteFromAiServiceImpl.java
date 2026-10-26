package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Note;
import com.readingnotes.repository.AiArtifactRepository;
import com.readingnotes.service.NoteFromAiService;
import com.readingnotes.service.NoteService;
import com.readingnotes.service.TagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * AI 产物一键存为笔记实现（API 文档 §9.9）
 *
 * <p>解析顺序：
 * <ol>
 *   <li>校验 source_type ∈ {AI_SUMMARY, AI_QA, AI_MINDMAP}</li>
 *   <li>取内容：优先 source_ai_version_id（ai_versions），其次 session_id（chat_messages 问答对）</li>
 *   <li>回填来源：AI 产物 target_type='book' 时写 source_book_id；会话则取其 book_id</li>
 *   <li>复用 {@link NoteService#create} 落库（统一主键/字数/版本号逻辑）</li>
 *   <li>auto_tag=true 时按 source_type 自动打类型标签</li>
 * </ol>
 */
@Slf4j
@Service
public class NoteFromAiServiceImpl implements NoteFromAiService {

    /** source_type → ai_versions.artifact_type */
    private static final Map<String, String> ARTIFACT_TYPE = Map.of(
            "AI_SUMMARY", "SUMMARY",
            "AI_QA", "ANSWER",
            "AI_MINDMAP", "MINDMAP");

    /** source_type → 自动标签名（auto_tag=true 时打） */
    private static final Map<String, String> AUTO_TAG = Map.of(
            "AI_SUMMARY", "AI摘要",
            "AI_QA", "AI问答",
            "AI_MINDMAP", "AI导图");

    private final AiArtifactRepository aiRepository;
    private final NoteService noteService;
    private final TagService tagService;

    public NoteFromAiServiceImpl(AiArtifactRepository aiRepository, NoteService noteService,
                                 TagService tagService) {
        this.aiRepository = aiRepository;
        this.noteService = noteService;
        this.tagService = tagService;
    }

    @Override
    public Note createFromAi(String sourceType, String sourceAiVersionId, String sessionId,
                             String title, boolean autoTag) {
        String type = (sourceType == null || sourceType.isBlank()) ? "AI_QA" : sourceType.trim();
        if (!ARTIFACT_TYPE.containsKey(type)) {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "source_type 只能为 AI_SUMMARY / AI_QA / AI_MINDMAP");
        }

        Note draft = new Note();
        draft.setSourceType(type);

        if (sourceAiVersionId != null && !sourceAiVersionId.isBlank()) {
            fillFromVersion(draft, sourceAiVersionId.trim(), type);
        } else if (sessionId != null && !sessionId.isBlank()) {
            fillFromSession(draft, sessionId.trim());
        } else {
            throw new BusinessException(ErrorCode.PARAM_ERROR,
                    "source_ai_version_id 与 session_id 至少填一个");
        }

        draft.setTitle((title == null || title.isBlank()) ? draft.getTitle() : title.trim());

        Note created = noteService.create(draft);
        if (autoTag) {
            tagService.bindTags(created.getNoteId(), List.of(AUTO_TAG.get(type)));
        }
        log.info("note created from ai: id={}, type={}, version={}, session={}",
                created.getNoteId(), type, sourceAiVersionId, sessionId);
        return created;
    }

    // ==================== 内容来源 ====================

    /** 从 ai_versions 取正式产物 */
    private void fillFromVersion(Note draft, String versionId, String type) {
        AiArtifactRepository.AiVersion v = aiRepository.findVersion(versionId);
        if (v == null) {
            throw new BusinessException(ErrorCode.VERSION_NOT_FOUND,
                    "指定的 AI 产物版本不存在: " + versionId);
        }
        String expected = ARTIFACT_TYPE.get(type);
        if (!expected.equals(v.artifactType())) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, String.format(
                    "产物类型不匹配：source_type=%s 对应 %s，该版本实际为 %s",
                    type, expected, v.artifactType()));
        }
        draft.setContent(v.content());
        draft.setSourceAiVersionId(versionId);
        // 摘要/导图归属某本书时回填来源书，便于后续溯源跳转
        if ("book".equals(v.targetType())) {
            draft.setSourceBookId(v.targetId());
        }
        if (draft.getTitle() == null) {
            String bookTitle = "book".equals(v.targetType())
                    ? aiRepository.findBookTitle(v.targetId()) : null;
            draft.setTitle(defaultTitle(type, bookTitle));
        }
    }

    /** 从 chat_messages 取最后一轮问答对 */
    private void fillFromSession(Note draft, String sessionId) {
        AiArtifactRepository.QaPair qa = aiRepository.findLatestQa(sessionId);
        if (qa == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该会话暂无可保存的问答内容");
        }
        String question = qa.question() == null ? "" : qa.question();
        String answer = qa.answer() == null ? "" : qa.answer();
        // Markdown 结构化沉淀，便于导出与后续检索
        draft.setContent("## 问题\n\n" + question + "\n\n## 回答\n\n" + answer);
        draft.setSourceBookId(aiRepository.findSessionBookId(sessionId));
        if (draft.getTitle() == null) {
            draft.setTitle(defaultTitle("AI_QA", truncate(question, 30)));
        }
    }

    // ==================== 工具 ====================

    private static String defaultTitle(String type, String hint) {
        String prefix = switch (type) {
            case "AI_SUMMARY" -> "AI摘要";
            case "AI_MINDMAP" -> "AI导图";
            default -> "AI问答";
        };
        return (hint == null || hint.isBlank()) ? prefix : prefix + " - " + hint;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max) + "...";
    }
}
