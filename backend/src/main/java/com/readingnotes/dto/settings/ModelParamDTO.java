package com.readingnotes.dto.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 模型参数请求/响应 DTO（接口 10.1）
 * snake_case 字段名匹配 API 文档规范
 */
@Data
public class ModelParamDTO {

    /** 温度 [0, 1] */
    @DecimalMin(value = "0.0", message = "temperature 必须 >= 0")
    @DecimalMax(value = "1.0", message = "temperature 必须 <= 1")
    private Double temperature;

    /** 最大输出 token [256, 4096] */
    @JsonProperty("max_tokens")
    @Min(value = 256, message = "max_tokens 必须 >= 256")
    @Max(value = 4096, message = "max_tokens 必须 <= 4096")
    private Integer maxTokens;

    /** nucleus 采样 [0.7, 1] */
    @JsonProperty("top_p")
    @DecimalMin(value = "0.7", message = "top_p 必须 >= 0.7")
    @DecimalMax(value = "1.0", message = "top_p 必须 <= 1")
    private Double topP;

    /** 是否开启流式输出 */
    private Boolean stream;
}
