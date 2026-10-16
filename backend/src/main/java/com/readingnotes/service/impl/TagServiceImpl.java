package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.entity.Tag;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.repository.TagRepository;
import com.readingnotes.service.TagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 标签服务实现（F4 第二阶段）
 *
 * <p>职责：显式创建标签（重名校验）、笔记创建时的自动打标（不存在则创建，幂等）、
 * 标签列表（含笔记数）。绑定走 note_tag_map（复合主键 + INSERT IGNORE 保证幂等）。</p>
 */
@Slf4j
@Service
public class TagServiceImpl implements TagService {

    private final TagRepository tagRepository;

    public TagServiceImpl(TagRepository tagRepository) {
        this.tagRepository = tagRepository;
    }

    // ==================== 列表 ====================

    @Override
    public List<Tag> list() {
        return tagRepository.listWithCount();
    }

    // ==================== 创建（POST /tags） ====================

    @Override
    public Tag create(String name, String color) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "标签名不能为空");
        }
        String trimmed = name.trim();
        if (tagRepository.findIdByName(trimmed) != null) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "标签已存在: " + trimmed);
        }
        Tag t = new Tag();
        t.setTagId(newTagId());
        t.setName(trimmed);
        t.setColor(color);
        t.setCreatedAt(NoteRepository.nowIso());
        tagRepository.insert(t);
        log.info("tag created: id={}, name={}", t.getTagId(), trimmed);
        return t;
    }

    // ==================== 绑定（笔记创建时自动打标） ====================

    @Override
    public void bindTags(String noteId, List<String> tagNames) {
        if (tagNames == null || tagNames.isEmpty()) {
            return;
        }
        for (String raw : tagNames) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String name = raw.trim();
            String tagId = tagRepository.findIdByName(name);
            if (tagId == null) {
                // 不存在则自动创建（文档 §9.1：tag_names 不存在则自动创建标签）
                Tag t = new Tag();
                t.setTagId(newTagId());
                t.setName(name);
                t.setColor(null);
                t.setCreatedAt(NoteRepository.nowIso());
                tagRepository.insert(t);
                tagId = t.getTagId();
                log.info("tag auto-created: name={}", name);
            }
            tagRepository.attach(noteId, tagId);
        }
    }

    // ==================== 工具 ====================

    /** 生成唯一 tag_id：tag_ + UUID 短片段（避免同毫秒随机数撞 id） */
    private static String newTagId() {
        return "tag_" + UUID.randomUUID().toString().substring(0, 8);
    }
}
