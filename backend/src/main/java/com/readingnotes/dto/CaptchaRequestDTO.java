package com.readingnotes.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 发送邮箱验证码请求。
 *
 * <p>scene 取值：register（注册）、login（验证码登录）、set_password（设置密码）。</p>
 */
@Data
public class CaptchaRequestDTO {

    /** 收件邮箱 */
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    /** 场景：register / login / set_password */
    @NotBlank(message = "场景不能为空")
    private String scene;
}
