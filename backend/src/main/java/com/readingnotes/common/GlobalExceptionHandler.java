package com.readingnotes.common;

import com.readingnotes.service.impl.BookServiceImpl.DuplicateBookException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.validation.ConstraintViolationException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理（API 文档 §2.2 / §3）。
 * <p>
 * 统一把异常转成 {@link Result} 信封，并保证 HTTP 状态码与 {@link ErrorCode} 一致：
 * <ul>
 *   <li>业务异常 → 对应错误码与 HTTP 状态</li>
 *   <li>参数校验异常 → 400（参数错误 10001）</li>
 *   <li>未捕获异常 → 500，只记日志不向客户端暴露堆栈</li>
 * </ul>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 业务异常：错误码、文案、HTTP 状态都由异常自身携带，这里只做透传与日志。
     *
     * @param e 业务异常
     * @return 与错误码匹配的 HTTP 状态 + 统一信封
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusiness(BusinessException e) {
        log.warn("business error code={} msg={} trace={}", e.getCode(), e.getMessage(), TraceContext.current());
        Result<Void> r = Result.fail(e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus()).body(r);
    }

    /**
     * 上传重名（20003）：响应 data 里带回 existing_book_id，前端据此弹二次确认。
     * 见 API 文档 §5.1「重名确认 409」。
     *
     * @param e 重名异常，携带已存在书籍的 id
     * @return 409 + {@code {"existing_book_id": "..."}}
     */
    @ExceptionHandler(DuplicateBookException.class)
    public ResponseEntity<Result<Map<String, Object>>> handleDuplicate(DuplicateBookException e) {
        log.info("duplicate book upload, existing={} trace={}", e.getExistingBookId(), TraceContext.current());
        Map<String, Object> data = new HashMap<>();
        data.put("existing_book_id", e.getExistingBookId());
        Result<Map<String, Object>> r = new Result<>();
        r.setCode(e.getCode());
        r.setMessage(e.getMessage());
        r.setData(data);
        r.setTraceId(TraceContext.current());
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
