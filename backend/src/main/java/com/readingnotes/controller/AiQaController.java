package com.readingnotes.controller;

import com.readingnotes.ai.CrossQaService;
import com.readingnotes.ai.NoteQaService;
import com.readingnotes.ai.QaService;
import com.readingnotes.ai.dto.CrossQaStreamRequest;
import com.readingnotes.ai.dto.NoteQaStreamRequest;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * AI 交互接口（API 文档 §7）
 * F3.2 书本内问答 SSE 流式：POST /api/v1/ai/qa/stream
 * F3.3 跨书问答 SSE 流式：POST /api/v1/ai/qa/cross/stream
 * F3.4 笔记问答 SSE 流式：POST /api/v1/ai/qa/note/stream
 *
 * SSE 协议（§13.1）：连接建立后每 15s 发送一次 `:ping` 注释行保持连接；
 * 连接超时/断开/异常时取消心跳任务。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ai")
public class AiQaController {

    /** SSE 心跳间隔（§13.1：每 15s 发送 :ping） */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15L;

    private final QaService qaService;
    private final CrossQaService crossQaService;
    private final NoteQaService noteQaService;
    private final ScheduledExecutorService heartbeatPool =
            Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "sse-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public AiQaController(QaService qaService, CrossQaService crossQaService, NoteQaService noteQaService) {
        this.qaService = qaService;
        this.crossQaService = crossQaService;
        this.noteQaService = noteQaService;
    }

    /**
     * 书本内问答（SSE 流式）
     * 协议见 API 文档 §13：meta → delta* → citation? → done/error
     */
    @PostMapping(value = "/qa/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter qaStream(@Valid @RequestBody QaStreamRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        if (!open(emitter)) {
            return emitter;
        }
        startHeartbeat(emitter);
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
        if (!open(emitter)) {
            return emitter;
        }
        startHeartbeat(emitter);
        crossQaService.streamCrossAnswer(req, emitter);
        return emitter;
    }

    /**
     * 基于笔记问答（SSE 流式，API 文档 §7.6）
     * scope：book_only / notes_only / book_and_notes（默认）
     * 事件流：meta → citation* → delta* → done/error
     */
    @PostMapping(value = "/qa/note/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter noteStream(@Valid @RequestBody NoteQaStreamRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);
        if (!open(emitter)) {
            return emitter;
        }
        startHeartbeat(emitter);
        noteQaService.streamNoteAnswer(req, emitter);
        return emitter;
    }

    // ==================== SSE 连接管理（§13） ====================

    /** 建立连接：先发一条注释行确认通道可用；失败则直接结束 */
    private boolean open(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("connected"));
            return true;
        } catch (IOException e) {
            log.warn("send connected comment failed, session may be closed");
            emitter.complete();
            return false;
        }
    }

    /** 每 15s 发一次 `:ping` 注释行（§13.1）；连接完成/超时/出错时取消 */
    private void startHeartbeat(SseEmitter emitter) {
        ScheduledFuture<?> future = heartbeatPool.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                // 连接已断开：停止心跳（异常会终止周期任务）
                throw new RuntimeException("sse closed, stop heartbeat", e);
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        emitter.onCompletion(() -> future.cancel(true));
        emitter.onTimeout(() -> future.cancel(true));
        emitter.onError(t -> future.cancel(true));
    }
}
