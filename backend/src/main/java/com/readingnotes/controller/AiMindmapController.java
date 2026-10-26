package com.readingnotes.controller;

import com.readingnotes.ai.MindmapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 思维导图接口（F3.5，API 文档 §7.7/7.8/7.9）
 *
 *  - POST  /api/v1/ai/mindmap                    提交导图任务（202 异步）
 *  - GET   /api/v1/ai/mindmap/{book_id}          查询导图（version=latest|N）
 *  - PATCH /api/v1/ai/mindmap/{version_id}       编辑导图内容（不递增版本号）
 *
 * 任务状态轮询复用 GET /api/v1/ai/tasks/{task_id}（AiTaskController）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai/mindmap")
public class AiMindmapController {

    private final MindmapService mindmapService;

    public AiMindmapController(MindmapService mindmapService) {
        this.mindmapService = mindmapService;
    }

    /** 提交导图任务：body={book_id, source, source_version_id?, chapter_path?} → 202 {task_id,...} */
    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String bookId = body.get("book_id") == null ? null : String.valueOf(body.get("book_id"));
        String source = body.get("source") == null ? null : String.valueOf(body.get("source"));
        String sourceVersionId = body.get("source_version_id") == null ? null
                : String.valueOf(body.get("source_version_id"));
        String chapterPath = body.get("chapter_path") == null ? null : String.valueOf(body.get("chapter_path"));
        String taskId = mindmapService.submitMindmap(bookId, source, sourceVersionId, chapterPath);
        mindmapService.runMindmap(taskId, bookId.trim(), source == null ? "summary" : source.trim(),
                sourceVersionId, chapterPath);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(mindmapService.taskAccepted(taskId, bookId.trim()));
    }

    /** 查询导图：GET /mindmap/{book_id}?version=latest|N */
    @GetMapping("/{book_id}")
    public Map<String, Object> get(@PathVariable("book_id") String bookId,
                                   @RequestParam(value = "version", required = false) String version) {
        return mindmapService.getMindmap(bookId, version);
    }

    /** 编辑导图：PATCH /mindmap/{version_id} body={content}（不递增版本号） */
    @PatchMapping("/{version_id}")
    public Map<String, Object> update(@PathVariable("version_id") String versionId,
                                      @RequestBody Map<String, Object> body) {
        String content = body.get("content") == null ? null : String.valueOf(body.get("content"));
        mindmapService.updateMindmap(versionId, content);
        return Map.of("version_id", versionId, "updated", true);
    }
}
