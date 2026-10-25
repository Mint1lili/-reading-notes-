package com.readingnotes.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设置/重置密码请求（需邮箱验证码认证，scene = set_password）。
 */
@Data
public class SetPasswordRequestDTO {

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "验证码不能为空")
    @Size(min = 6, max = 6, message = "验证码为 6 位数字")
    private String code;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 位之间")
    private String password;
}
