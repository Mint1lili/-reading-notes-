package com.readingnotes.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

/**
 * 全局异常处理（API 文档 §2.2 / §3）
 * - 业务异常 → 对应错误码与 HTTP 状态
 * - 参数校验异常 → 400（参数错误 10001）
 * - 未捕获异常 → 500，不向客户端暴露堆栈
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("business error code={} msg={} trace={}", e.getCode(), e.getMessage(), TraceContext.current());
        Result<Void> r = Result.fail(e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus()).body(r);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    public ResponseEntity<Result<Void>> handleValidation(Exception e) {
        String msg = "请求参数有误";
        if (e instanceof MethodArgumentNotValidException ex) {
            FieldError fe = ex.getBindingResult().getFieldError();
            if (fe != null) {
                msg = fe.getDefaultMessage();
            }
        } else if (e instanceof ConstraintViolationException ex) {
            msg = ex.getConstraintViolations().stream().findFirst()
                    .map(v -> v.getMessage()).orElse(msg);
        }
        log.warn("validation error msg={} trace={}", msg, TraceContext.current());
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.PARAM_ERROR.getCode(), msg));
    }

    /**
     * 请求体缺失 / JSON 解析失败 → 400 参数错误（10001）
     *
     * <p>不落地到兜底 500：客户端少传 body 或写错 JSON 属于调用方问题，
     * 返回"服务内部错误"会误导前端与调试工具（Apifox 响应校验）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("request body unreadable msg={} trace={}", e.getMessage(), TraceContext.current());
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.PARAM_ERROR.getCode(), "请求体为空或 JSON 格式有误"));
    }

    /**
     * 请求路径不存在 / 该路径不支持此 HTTP 方法 → 404 资源不存在（10002）
     *
     * <p>常见于 Apifox 里 path 参数留空（/notes/ 而非 /notes/{note_id}）或方法写错，
     * 不应返回"服务内部错误"。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("resource not found path={} trace={}", e.getResourcePath(), TraceContext.current());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(ErrorCode.NOT_FOUND.getCode(),
                        "接口或资源不存在，请检查请求路径"));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleUploadSize(MaxUploadSizeExceededException e) {
        log.warn("upload too large trace={}", TraceContext.current());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Result.fail(ErrorCode.FILE_TOO_LARGE.getCode(), ErrorCode.FILE_TOO_LARGE.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleOther(Exception e) {
        log.error("unhandled error trace={}", TraceContext.current(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage()));
    }
}
