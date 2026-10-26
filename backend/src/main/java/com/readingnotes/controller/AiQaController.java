package com.readingnotes.controller;

import com.readingnotes.ai.CrossQaService;
import com.readingnotes.ai.QaService;
import com.readingnotes.ai.dto.CrossQaStreamRequest;
import com.readingnotes.ai.dto.QaStreamRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * AI 交互接口（API 文档 §7）
 * F3.2 书本内问答 SSE 流式：POST /api/v1/ai/qa/stream
 * F3.3 跨书问答 SSE 流式：POST /api/v1/ai/qa/cross/stream
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class AiQaController {

    private final QaService qaService;
    private final CrossQaService crossQaService;

    public AiQaController(QaService qaService, CrossQaService crossQaService) {
        this.qaService = qaService;
        this.crossQaService = crossQaService;
    }

    /**
     * 书本内问答（SSE 流式）
     * 协议见 API 文档 §13：meta → delta* → citation? → done/error
     */
    @PostMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter qaStream(@Valid @RequestBody QaStreamRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        // 先发一条占位注释行建立连接（心跳约定 §13.1）
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            log.warn("send connected comment failed, session may be closed");
            emitter.complete();
            return emitter;
        }
        qaService.streamAnswer(req, emitter);
        return emitter;
    }

    /**
     * 跨书问答（SSE 流式，API 文档 §7.5）
     * book_ids 为空 = 全部 READY 书籍；include_notes 默认 true（纳入 notes_all）
     * 事件流：meta → citation* → delta* → done/error；拒答 done(refused=true)
     */
    @PostMapping(value = "/qa/cross/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter crossStream(@Valid @RequestBody CrossQaStreamRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            log.warn("send connected comment failed, session may be closed");
            emitter.complete();
            return emitter;
        }
        crossQaService.streamCrossAnswer(req, emitter);
        return emitter;
    }
}
