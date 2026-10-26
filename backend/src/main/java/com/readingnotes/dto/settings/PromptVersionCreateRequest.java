package com.readingnotes.dto.settings;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建 Prompt 新版本请求（接口 10.3 POST）
 */
@Data
public class PromptVersionCreateRequest {

    @NotBlank(message = "content 不能为空")
    private String content;

    private String description;
}
