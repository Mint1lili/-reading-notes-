package com.readingnotes.common;

import lombok.Getter;

/**
 * 错误码体系（API 文档 §3）
 * 0 成功 | 1xxxx 通用 | 2xxxx 书籍 | 3xxxx 构建 | 4xxxx AI | 5xxxx 笔记 | 6xxxx 设置 | 7xxxx 外部依赖
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "success", 200),

    // ===== 通用 1xxxx =====
    PARAM_ERROR(10001, "请求参数有误", 400),
    NOT_FOUND(10002, "资源不存在", 404),
    RATE_LIMITED(10003, "操作过于频繁，请稍后再试", 429),
    INTERNAL_ERROR(10004, "服务内部错误，请查看日志", 500),

    // ===== 书籍 F1 2xxxx =====
    NOT_PDF(20001, "请上传 PDF 文件", 415),
    FILE_TOO_LARGE(20002, "文件不能超过 50MB", 413),
    DUPLICATE_BOOK(20003, "已存在同名书籍，是否继续上传？", 409),
    BOOK_BUILDING(20004, "书籍正在构建中，请先等待或取消", 409),
    BOOK_NOT_FOUND(20005, "书籍不存在或已删除", 404),
    PDF_ENCRYPTED(20006, "该 PDF 已加密，请先解除密码保护", 422),
    PDF_TOO_MANY_PAGES(20007, "本书页数超过上限，暂不支持", 422),
    PDF_DAMAGED(20008, "文件已损坏或不是有效的 PDF", 422),
    UPLOAD_SESSION_GONE(20009, "上传会话不存在或已过期，请重新上传", 410),
    FILE_NOT_FOUND_ON_DISK(20010, "原书文件已丢失，请重新上传", 410),

    // ===== 构建 F2 3xxxx =====
    SCANNED_PDF(30001, "未检测到文本内容，可能是扫描件，本期暂不支持", 422),
    EMBED_TIMEOUT(30002, "向量化服务超时，请重试", 503),
    CHAPTER_RATE_LOW(30003, "章节识别率过低，问答将退化为纯向量检索", 500),
    BUILD_FAILED(30004, "构建失败，可点击重试", 500),
    BUILD_TASK_DONE(30005, "构建任务不存在或已完成", 409),

    // ===== AI F3 4xxxx =====
    AI_UNAVAILABLE(40001, "AI 服务暂时不可用，请重试", 503),
    NOT_MENTIONED(40002, "本书中未提及相关内容", 200),
    VERSION_NOT_FOUND(40003, "指定的 AI 产物版本不存在", 404),
    AI_TASK_RUNNING(40004, "AI 任务正在生成中，请稍候", 409),
    CROSS_SCOPE_EMPTY(40005, "未选择任何书籍，无法进行跨书问答", 400),

    // ===== 笔记 F4 5xxxx =====
    NOTE_NOT_FOUND(50001, "笔记不存在或已删除", 404),
    NOTE_INDEX_ROLLBACK(50002, "内容已恢复，未保存（向量同步失败）", 500),
    NOTE_INDEX_FAILED(50003, "笔记入库失败，请重试", 500),
    NOTE_VERSION_CONFLICT(50004, "笔记已被他人更新，是否重载？", 409),

    // ===== 设置 F5 6xxxx =====
    PROMPT_NOT_FOUND(60001, "Prompt 模板不存在", 404),
    PROMPT_SYNTAX_ERROR(60002, "Prompt 模板语法错误", 400),
    BACKUP_FAILED(60003, "备份失败，请检查磁盘空间", 500),
    RESTORE_FAILED(60004, "恢复失败，文件未损坏前请勿继续", 500),
    USAGE_LIMIT_EXCEEDED(60005, "已超出单日预算，AI 调用已暂停", 400),

    // ===== 外部依赖 7xxxx =====
    CHROMA_DOWN(70001, "知识库服务未启动，请检查 Chroma", 503),
    DIM_MISMATCH(70002, "Chroma 与 Embedding 维度不一致", 503),
    DOUBAO_DOWN(70003, "豆包 API 不可用", 503),
    DOUBAO_AUTH_FAILED(70004, "豆包 API Key 未配置或无效，请检查 DOUBAO_API_KEY", 503),
    DOUBAO_MODEL_NOT_FOUND(70005, "豆包模型不存在或未开通，请检查模型名/接入点 ID", 503),
    DOUBAO_BAD_REQUEST(70006, "豆包 API 请求参数异常", 400),
    DOUBAO_RATE_LIMITED(70007, "豆包 API 触发限流，请稍后重试", 429),
    DOUBAO_BATCH_TOO_LARGE(70008, "豆包 Embedding 单次请求条数超出限制", 400),
    DOUBAO_MODEL_NOT_OPEN(70009,
            "该模型未在当前账号开通，请到方舟控制台「开通管理」开通后再试"
                    + "（https://console.volcengine.com/ark/region:ark+cn-beijing/openManagement）", 503),

    // ===== 认证 8xxxx =====
    EMAIL_TAKEN(80001, "该邮箱已注册", 409),
    USER_NOT_FOUND(80002, "该邮箱未注册，请先注册", 401),
    CAPTCHA_EXPIRED(80003, "验证码已过期，请重新获取", 400),
    CAPTCHA_WRONG(80004, "验证码错误", 400),
    CAPTCHA_TOO_FREQUENT(80005, "验证码发送过于频繁，请稍后再试", 429),
    PASSWORD_NOT_SET(80006, "该账号未设置密码，请使用验证码登录或先设置密码", 400),
    PASSWORD_WRONG(80007, "邮箱或密码错误", 401),
    UNAUTHORIZED(80008, "未登录或登录已过期", 401),
    MAIL_NOT_CONFIGURED(80009, "邮件服务未配置，请填写邮箱账号与 SMTP 授权码", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
