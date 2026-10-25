package com.readingnotes.repository;

import com.readingnotes.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 用户数据访问层（JdbcTemplate + MySQL，对齐项目 Repository 风格）
 *
 * <p>users 表结构见项目根目录《auth_users表-邮箱验证码注册登录.sql》。</p>
 */
@Slf4j
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 通用行映射 */
    private static final RowMapper<User> MAPPER = (rs, i) -> {
        User u = new User();
        u.setUserId(rs.getLong("user_id"));
        u.setEmail(rs.getString("email"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setNickname(rs.getString("nickname"));
        u.setStatus(rs.getInt("status"));
        u.setCreatedAt(rs.getString("created_at"));
        u.setUpdatedAt(rs.getString("updated_at"));
        return u;
    };

    // ==================== 查询 ====================

    /** 按邮箱查用户（不存在返回 null） */
    public User findByEmail(String email) {
        List<User> list = jdbc.query(
                "SELECT * FROM users WHERE email = ?", MAPPER, email);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按主键查用户（不存在返回 null） */
    public User findById(Long userId) {
        List<User> list = jdbc.query(
                "SELECT * FROM users WHERE user_id = ?", MAPPER, userId);
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 写入 ====================

    /**
     * 插入用户，返回自增主键。
     *
     * <p>passwordHash 允许为 null（验证码注册阶段不设密码），
     * status 为 null 时按 1（正常）写入。</p>
     */
    public long insert(User u) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO users (email, password_hash, nickname, status, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, u.getEmail());
            ps.setString(2, u.getPasswordHash());
            ps.setString(3, u.getNickname());
            ps.setInt(4, u.getStatus() == null ? 1 : u.getStatus());
            ps.setString(5, u.getCreatedAt());
            ps.setString(6, u.getUpdatedAt());
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** 更新密码哈希（设置/重置密码用），返回影响行数 */
    public int updatePassword(Long userId, String passwordHash) {
        return jdbc.update(
                "UPDATE users SET password_hash = ?, updated_at = ? WHERE user_id = ?",
                passwordHash, NoteRepository.nowIso(), userId);
    }
}
