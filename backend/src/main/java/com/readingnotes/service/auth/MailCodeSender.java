package com.readingnotes.service.auth;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送服务：发送注册 / 登录 / 设置密码验证码。
 *
 * <p>发件人取 {@code spring.mail.username}；未配置账号或授权码时直接抛业务异常，避免静默失败。
 * 借鉴 xiangmu4 的 MailCodeSender 精简实现。</p>
 */
@Slf4j
@Service
public class MailCodeSender {

    private final JavaMailSender mailSender;

    /** 发件邮箱账号（同时作为 SMTP 登录用户） */
    @Value("${spring.mail.username:}")
    private String username;

    /** SMTP 授权码（不是邮箱登录密码） */
    @Value("${spring.mail.password:}")
    private String password;

    public MailCodeSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * 发送验证码邮件，按场景组装标题与正文。
     *
     * @param email 收件邮箱
     * @param scene 场景（register / login / set_password）
     * @param code  6 位验证码
     */
    public void sendCaptchaCode(String email, String scene, String code) {
        String subject;
        String text;
        switch (scene == null ? "" : scene) {
            case "register" -> {
                subject = "【阅读笔记】注册验证码";
                text = "您的注册验证码是 " + code + "，5 分钟内有效。请勿泄露给他人。";
            }
            case "login" -> {
                subject = "【阅读笔记】登录验证码";
                text = "您的登录验证码是 " + code + "，5 分钟内有效。请勿泄露给他人。";
            }
            case "set_password" -> {
                subject = "【阅读笔记】设置密码验证码";
                text = "您的设置密码验证码是 " + code + "，5 分钟内有效。请勿泄露给他人。";
            }
            default -> {
                subject = "【阅读笔记】验证码";
                text = "您的验证码是 " + code + "，5 分钟内有效。请勿泄露给他人。";
            }
        }
        send(email, subject, text);
    }

    /** 统一发送纯文本邮件，发送前校验邮件配置是否完整 */
    private void send(String email, String subject, String text) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new BusinessException(ErrorCode.MAIL_NOT_CONFIGURED);
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(username);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(text);
        mailSender.send(message);
        log.info("验证码邮件发送成功，to={}，subject={}", email, subject);
    }
}
