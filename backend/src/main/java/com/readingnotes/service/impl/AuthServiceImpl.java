package com.readingnotes.service.impl;

import com.readingnotes.common.BusinessException;
import com.readingnotes.common.ErrorCode;
import com.readingnotes.dto.TokenVO;
import com.readingnotes.entity.User;
import com.readingnotes.repository.NoteRepository;
import com.readingnotes.repository.UserRepository;
import com.readingnotes.security.JwtUtils;
import com.readingnotes.service.AuthService;
import com.readingnotes.service.auth.MailCodeSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Random;

/**
 * 认证服务实现（借鉴 xiangmu4 的 AuthServiceImpl 精简）。
 *
 * <p>依赖：UserRepository（JdbcTemplate 数据访问）、BCryptPasswordEncoder（密码哈希）、
 * JwtUtils（签发/解析 token）、StringRedisTemplate（验证码存储，5 分钟 TTL + 60 秒限频）、
 * MailCodeSender（真实发送验证码邮件）。</p>
 *
 * <p>Redis key 约定：captcha:code:{scene}:{email} 验证码本体；
 * captcha:limit:{scene}:{email} 限频标记。校验成功后立即删除，防重放。</p>
 */
@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private static final String CODE_PREFIX = "captcha:code:";
    private static final String LIMIT_PREFIX = "captcha:limit:";
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration LIMIT_TTL = Duration.ofSeconds(60);
    private static final int CODE_LENGTH = 6;

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate redisTemplate;
    private final MailCodeSender mailCodeSender;

    /** Access token 有效期（毫秒），用于 TokenVO.expiresIn */
    @Value("${jwt.expire-millis:7200000}")
    private long expireMillis;

    public AuthServiceImpl(UserRepository userRepository,
                           BCryptPasswordEncoder passwordEncoder,
                           JwtUtils jwtUtils,
                           StringRedisTemplate redisTemplate,
                           MailCodeSender mailCodeSender) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtils = jwtUtils;
        this.redisTemplate = redisTemplate;
        this.mailCodeSender = mailCodeSender;
    }

    @Override
    public void sendCaptcha(String email, String scene) {
        String target = email.trim();
        String sceneNorm = scene.trim();

        // 限频：60 秒内同一邮箱同一场景只能发一次
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(LIMIT_PREFIX + sceneNorm + ":" + target, "1", LIMIT_TTL);
        if (!Boolean.TRUE.equals(first)) {
            throw new BusinessException(ErrorCode.CAPTCHA_TOO_FREQUENT);
        }

        // 生成 6 位数字验证码
        Random random = new Random();
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(random.nextInt(10));
        }
        String code = sb.toString();
        redisTemplate.opsForValue().set(CODE_PREFIX + sceneNorm + ":" + target, code, CODE_TTL);

        // 真实发送邮件；失败时清理限频与验证码 key，允许重新获取
        try {
            mailCodeSender.sendCaptchaCode(target, sceneNorm, code);
        } catch (BusinessException e) {
            redisTemplate.delete(LIMIT_PREFIX + sceneNorm + ":" + target);
            redisTemplate.delete(CODE_PREFIX + sceneNorm + ":" + target);
            log.error("验证码邮件发送失败（配置缺失），email={}，scene={}", target, sceneNorm);
            throw e;
        } catch (MailException e) {
            redisTemplate.delete(LIMIT_PREFIX + sceneNorm + ":" + target);
            redisTemplate.delete(CODE_PREFIX + sceneNorm + ":" + target);
            log.error("验证码邮件发送失败，email={}，scene={}", target, sceneNorm, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "验证码邮件发送失败，请稍后重试");
        }
        log.info("[Captcha Mail] 向 {} 发送 {} 场景验证码", target, sceneNorm);
    }

    @Override
    public TokenVO register(String email, String code) {
        String target = email.trim();
        // 1. 校验验证码（统一 login 场景；只校验不删除，失败可重试）
        verifyCaptcha(target, "login", code);

        // 2. 查用户：已注册直接登录（幂等），未注册创建新用户
        User user = userRepository.findByEmail(target);
        if (user == null) {
            String now = NoteRepository.nowIso();
            user = new User();
            user.setEmail(target);
            user.setPasswordHash(null);
            user.setNickname(null);
            user.setStatus(1);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            long userId = userRepository.insert(user);
            if (userId == 0L) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "用户创建失败，请稍后重试");
            }
            user.setUserId(userId);   // JdbcTemplate 不自增回填，手动回填主键
            log.info("用户注册成功，userId={}，email={}", userId, target);
        }
        requireActive(user);

        // 3. 业务成功后删除验证码，保证一次性使用
        redisTemplate.delete(CODE_PREFIX + "login:" + target);

        return issueToken(user.getUserId(), target);
    }

    @Override
    public TokenVO loginByCode(String email, String code) {
        String target = email.trim();
        // 1. 校验验证码（校验成功后删除，防重放）
        verifyCaptcha(target, "login", code);
        redisTemplate.delete(CODE_PREFIX + "login:" + target);
        redisTemplate.delete(LIMIT_PREFIX + "login:" + target);

        // 2. 查用户：未注册自动注册（邮箱+验证码即身份凭证，一键成为新用户）
        User user = userRepository.findByEmail(target);
        if (user == null) {
            String now = NoteRepository.nowIso();
            user = new User();
            user.setEmail(target);
            user.setPasswordHash(null);
            user.setNickname(null);
            user.setStatus(1);
            user.setCreatedAt(now);
            user.setUpdatedAt(now);
            long userId = userRepository.insert(user);
            if (userId == 0L) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "用户创建失败，请稍后重试");
            }
            user.setUserId(userId);   // JdbcTemplate 不自增回填，手动回填主键
            log.info("验证码登录自动注册成功，userId={}，email={}", userId, target);
        }
        requireActive(user);

        return issueToken(user.getUserId(), target);
    }

    @Override
    public TokenVO loginByPassword(String email, String password) {
        String target = email.trim();
        User user = userRepository.findByEmail(target);
        if (user == null) {
            throw new BusinessException(ErrorCode.PASSWORD_WRONG);
        }
        requireActive(user);

        // 未设置过密码（验证码注册用户）不能走密码登录
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new BusinessException(ErrorCode.PASSWORD_NOT_SET);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.PASSWORD_WRONG);
        }

        return issueToken(user.getUserId(), target);
    }

    @Override
    public void setPassword(String email, String code, String password) {
        String target = email.trim();
        // 1. 校验 set_password 场景验证码（只校验不删除，失败可重试）
        verifyCaptcha(target, "set_password", code);

        // 2. 用户必须已注册
        User user = userRepository.findByEmail(target);
        if (user == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }
        requireActive(user);

        // 3. 写入 BCrypt 哈希
        String hash = passwordEncoder.encode(password);
        if (userRepository.updatePassword(user.getUserId(), hash) == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "密码设置失败，请稍后重试");
        }

        // 4. 业务成功后删除验证码
        redisTemplate.delete(CODE_PREFIX + "set_password:" + target);
        log.info("用户设置密码成功，userId={}，email={}", user.getUserId(), target);
    }

    @Override
    public User getCurrentUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        User user = userRepository.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        requireActive(user);
        // 脱敏：不返回密码哈希
        user.setPasswordHash(null);
        return user;
    }

    // ==================== 私有方法 ====================

    /** 校验验证码（Redis key 按 scene + 邮箱隔离）；只校验不删除，业务成功后由调用方删除 */
    private void verifyCaptcha(String email, String scene, String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "验证码不能为空");
        }
        String cached = redisTemplate.opsForValue().get(CODE_PREFIX + scene + ":" + email);
        if (cached == null) {
            throw new BusinessException(ErrorCode.CAPTCHA_EXPIRED);
        }
        if (!cached.equalsIgnoreCase(code)) {
            throw new BusinessException(ErrorCode.CAPTCHA_WRONG);
        }
    }

    /** 冻结账号拦截 */
    private void requireActive(User user) {
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号已被冻结，请联系管理员");
        }
    }

    /** 签发 token，组装 TokenVO */
    private TokenVO issueToken(Long userId, String email) {
        String token = jwtUtils.generateToken(userId, email);
        return new TokenVO(token, "Bearer", expireMillis / 1000, userId, email);
    }
}
