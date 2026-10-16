package com.readingnotes.controller;

import com.readingnotes.common.Result;
import com.readingnotes.entity.Tag;
import com.readingnotes.service.TagService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 标签管理接口（API 文档 §9.12）
 *
 * <ul>
 *   <li>GET /api/v1/tags   标签列表（含各标签笔记数 note_count）</li>
 *   <li>POST /api/v1/tags  创建标签（201；name 重复返回 10001）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tags")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    public Result<Map<String, Object>> list() {
        Map<String, Object> data = new HashMap<>();
        data.put("items", tagService.list());
        return Result.ok(data);
    }

    /** 请求体：{ "name": "方法论", "color": "#E6A23C" } */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<Tag> create(@RequestBody Map<String, Object> body) {
        String name = body.get("name") == null ? null : body.get("name").toString();
        String color = body.get("color") == null ? null : body.get("color").toString();
        return Result.ok(tagService.create(name, color));
    }
}
