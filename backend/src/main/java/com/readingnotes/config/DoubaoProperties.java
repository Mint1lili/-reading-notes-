package com.readingnotes.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 豆包大模型 API 配置（application.yml 的 doubao.*）
 * key 只允许来自环境变量 DOUBAO_API_KEY，绝不写入代码或配置文件
 */
@Data
@Component
@ConfigurationProperties(prefix = "doubao")
public class DoubaoProperties {

    /** 环境变量注入，空则 AI 调用降级（页面提示配置缺失） */
    private String apiKey;

    /** 方舟 API 根地址，需带版本段（{@code https://ark.cn-beijing.volces.com/api/v3}） */
    private String baseUrl;

    /** 对话模型名或接入点 ID（ep-xxx），用于摘要、问答、评估 */
    private String chatModel;

    /** 向量化模型名或接入点 ID，用于知识库构建与笔记索引 */
    private String embeddingModel;

    /** 向量维度：必须与 Chroma 集合维度一致；doubao-embedding-text-240715 默认 2560，可降维 1024/2048 */
    private int embeddingDim;

    /** 单次 /embeddings 请求的文本条数（客户端会按此切批，并在服务端上限更小时自动下调） */
    private int batchSize;

    /** 429/5xx/超时 的最大重试次数 */
    private int maxRetries;

    /** Embedding 单次请求超时（秒），需求 §8 要求 30s 超时提示「向量化服务超时」 */
    private int embeddingTimeoutSeconds = 35;

    /** TCP 建连超时（秒） */
    private int connectTimeoutSeconds = 10;

    /** Chat 非流式单次请求超时（秒） */
    private int chatTimeoutSeconds = 120;

    /** Chat 流式响应整体超时（秒），长回答需要留足时间 */
    private int streamTimeoutSeconds = 300;

    /** 退避基数（毫秒）：1s→2s→4s */
    private long retryBaseMs = 1000;

    /** 单次退避上限（毫秒），同时约束 Retry-After 的等待时长 */
    private long retryMaxMs = 8000;

    /** 单条文本字符上限（模型单条 4096 token，中文约 1 token/字，留安全余量） */
    private int maxInputChars = 6000;

    /** 流式对话是否请求 usage 统计（仅部分模型/接入点支持，默认关闭以免 400） */
    private boolean streamIncludeUsage = false;

    /**
     * 深度思考开关：disabled / enabled / auto（auto=不发送该参数，用模型默认）。
     * <p>seed-2.x turbo、pro 等推理模型默认开启思考，首字延迟可达数十秒；
     * 阅读问答场景不需要，默认 disabled。模型不支持时会自动去掉参数重试。
     */
    private String thinking = "disabled";

    /**
     * 向量化接口形态：
     * <ul>
     *   <li>{@code text} —— POST /embeddings，input 为字符串数组（doubao-embedding-text-*）</li>
     *   <li>{@code multimodal} —— POST /embeddings/multimodal，input 为
     *       [{type:text,text:...}]（doubao-embedding-vision-* 仅暴露此端点）</li>
     *   <li>{@code auto} —— 先按 text 调，遇参数/路径不兼容自动切换到 multimodal 并记住（默认）</li>
     * </ul>
     */
    private String embeddingMode = "auto";

    /** 多模态向量化可选的检索指令（留空则不发送） */
    private String embeddingInstruction = "";

    /** 多模态向量化返回条数与请求不符时，是否退回逐条请求（慢但稳） */
    private boolean embeddingSequentialFallback = true;
}
