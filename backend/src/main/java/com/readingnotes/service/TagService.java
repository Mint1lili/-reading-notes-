package com.readingnotes.service;

import com.readingnotes.entity.Tag;

import java.util.List;

/**
 * 标签服务（F4 第二阶段）——接口定义，实现见 TagServiceImpl
 *
 * <p>覆盖 API 文档 §9.12（标签列表/创建）+ §9.1 笔记创建自动打标签 + §9.2 按标签过滤。</p>
 */
public interface TagService {

    /** 标签列表（含各标签笔记数 note_count） */
    List<Tag> list();

    /**
     * 创建标签（POST /tags）
     *
     * @throws com.readingnotes.common.BusinessException name 为空或已存在时抛 10001
     */
    Tag create(String name, String color);

    /**
     * 给笔记绑定标签（创建笔记时 tag_names 自动打标，幂等）：
     * 标签不存在则自动创建，已存在则复用；重复绑定静默跳过。
     * 空列表/空名直接忽略。
     */
    void bindTags(String noteId, List<String> tagNames);
}
