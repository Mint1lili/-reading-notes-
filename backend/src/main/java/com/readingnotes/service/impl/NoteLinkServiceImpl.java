package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.NoteLink;
import com.readingnotes.repository.NoteLinkRepository;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.service.NoteLinkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 双向链接服务实现（F4 第三阶段）
 *
 * <p>职责：链接查询（出/入链视角组装，目标被永久删除时标题兜底为
 * "已删除笔记（链接失效）"、target_jumpable 强制 false）；创建（两端笔记
 * 存在性校验 + 同对幂等）。</p>
 */
@Slf4j
@Service
public class NoteLinkServiceImpl implements NoteLinkService {

    /** 目标被永久删除后查询兜底标题（文档 §9.7 第 4 步约定文案） */
    private static final String DELETED_TITLE = "已删除笔记（链接失效）";

    private final NoteLinkRepository noteLinkRepository;
    private final NoteRepository noteRepository;

    public NoteLinkServiceImpl(NoteLinkRepository noteLinkRepository,
                               NoteRepository noteRepository) {
        this.noteLinkRepository = noteLinkRepository;
        this.noteRepository = noteRepository;
    }

    // ==================== 查询 ====================

    @Override
    public Map<String, Object> list(String noteId) {
        // 当前笔记必须存在（未软删），否则 50001
        noteRepository.findById(noteId);

        Map<String, Object> data = new HashMap<>();
        data.put("outgoing", toOutgoing(noteLinkRepository.findOutgoing(noteId)));
        data.put("incoming", toIncoming(noteLinkRepository.findIncoming(noteId)));
        return data;
    }

    /** 出链：target 标题取 join 值，目标不存在时兜底文案 + jumpable=false */
    private List<Map<String, Object>> toOutgoing(List<NoteLink> links) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (NoteLink l : links) {
            Map<String, Object> item = new HashMap<>();
            item.put("link_id", l.getLinkId());
            item.put("target_note_id", l.getTargetNoteId());
            item.put("context", l.getContext());
            boolean targetExists = l.getTargetTitle() != null;
            item.put("target_title", targetExists ? l.getTargetTitle() : DELETED_TITLE);
            item.put("target_jumpable", targetExists && l.getTargetJumpable() != null
                    && l.getTargetJumpable() == 1);
            result.add(item);
        }
        return result;
    }

    /** 入链：来源标题取 join 值，来源不存在时兜底文案 */
    private List<Map<String, Object>> toIncoming(List<NoteLink> links) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (NoteLink l : links) {
            Map<String, Object> item = new HashMap<>();
            item.put("link_id", l.getLinkId());
            item.put("source_note_id", l.getSourceNoteId());
            item.put("context", l.getContext());
            item.put("source_title",
                    l.getSourceTitle() != null ? l.getSourceTitle() : DELETED_TITLE);
            result.add(item);
        }
        return result;
    }

    // ==================== 创建 ====================

    @Override
    public void create(String sourceNoteId, String targetNoteId, String context) {
        // 来源笔记必须存在
        noteRepository.findById(sourceNoteId);
        // 目标笔记必须存在且未软删（自链接也允许，但一般无意义，不特殊处理）
        if (targetNoteId == null || targetNoteId.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "target_note_id 必填");
        }
        noteRepository.findById(targetNoteId);
        // 幂等：同对 (source,target) 已存在则直接返回
        if (noteLinkRepository.exists(sourceNoteId, targetNoteId)) {
            log.info("link already exists, skip: {} -> {}", sourceNoteId, targetNoteId);
            return;
        }
        NoteLink link = new NoteLink();
        link.setLinkId("link_" + UUID.randomUUID().toString().substring(0, 8));
        link.setSourceNoteId(sourceNoteId);
        link.setTargetNoteId(targetNoteId);
        link.setContext(context);
        noteLinkRepository.insert(link);
        log.info("note link created: {} -> {}", sourceNoteId, targetNoteId);
    }
}
