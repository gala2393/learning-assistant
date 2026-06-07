package com.mytext.learningassistant.user;

import java.time.LocalDateTime;
import java.time.ZoneId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 用户实体类 — 对应数据库中的 sys_user 表，存储所有用户的基本信息。
 * <p>
 * 使用 JPA 注解进行 ORM 映射，字段说明：
 * <ul>
 *   <li>id — 用户唯一标识（自增主键）</li>
 *   <li>username — 用户名（唯一，用于登录）</li>
 *   <li>email — 邮箱（唯一，可用于登录和找回密码）</li>
 *   <li>passwordHash — 密码的哈希值（不明文存储密码）</li>
 *   <li>nickname — 用户昵称（显示名称）</li>
 *   <li>avatar — 头像（Base64 图片数据或预设头像标识）</li>
 *   <li>role — 用户角色（USER 普通用户 / ADMIN 管理员）</li>
 *   <li>status — 账户状态（ACTIVE 正常 / DISABLED 禁用）</li>
 *   <li>tokenVersion — Token 版本号，递增后可使所有旧 Token 失效</li>
 *   <li>createdAt / updatedAt — 创建和更新时间（北京时间）</li>
 * </ul>
 */
@Entity
@Table(name = "sys_user")
public class UserEntity {

    /** 北京时间时区，用于记录创建和更新时间 */
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    /** 用户唯一标识（自增主键） */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户名（唯一，用于登录，最大 64 字符） */
    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** 邮箱地址（唯一，可用于登录和找回密码，最大 128 字符） */
    @Column(unique = true, length = 128)
    private String email;

    /** 密码哈希值（不明文存储密码，最大 128 字符） */
    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;

    /** 用户昵称（显示名称，最大 64 字符） */
    @Column(nullable = false, length = 64)
    private String nickname;

    /** 头像数据（Base64 图片数据或 "preset:xxx" 预设头像标识，使用 TEXT 类型存储大文本） */
    @Column(name = "avatar", columnDefinition = "TEXT")
    private String avatar;

    /** 用户角色（USER 普通用户 / ADMIN 管理员），以字符串形式存储到数据库 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    /** 账户状态（ACTIVE 正常 / DISABLED 禁用），以字符串形式存储到数据库 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    /** 记录创建时间（北京时间） */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 记录最后更新时间（北京时间） */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Token 版本号 — 用于实现 Token 主动失效机制。
     * 当用户修改密码、登出或被管理员封禁时，递增此值，
     * 使所有持有旧版本号 Token 的请求立即失效。
     */
    @Column(name = "token_version", nullable = false)
    private Integer tokenVersion;

    /**
     * JPA 生命周期回调 — 在实体首次保存到数据库之前自动调用。
     * 设置默认的创建时间、更新时间、角色、状态和 Token 版本号。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(BEIJING_ZONE);
        if (createdAt == null) {
            createdAt = now;  // 设置创建时间
        }
        updatedAt = now;      // 设置更新时间
        if (role == null) {
            role = UserRole.USER;         // 默认角色为普通用户
        }
        if (status == null) {
            status = UserStatus.ACTIVE;   // 默认状态为正常
        }
        if (tokenVersion == null) {
            tokenVersion = 0;             // 默认 Token 版本号为 0
        }
    }

    /**
     * JPA 生命周期回调 — 在实体更新到数据库之前自动调用。
     * 更新最后修改时间，确保 tokenVersion 不为 null。
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(BEIJING_ZONE);  // 更新最后修改时间
        if (tokenVersion == null) {
            tokenVersion = 0;  // 安全兜底
        }
    }

    // ========== 以下是 getter/setter 方法 ==========

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public UserRole getRole() { return role; }
    public void setRole(UserRole role) { this.role = role; }

    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    /**
     * 获取 Token 版本号，null 安全（默认返回 0）。
     *
     * @return Token 版本号
     */
    public Integer getTokenVersion() { return tokenVersion == null ? 0 : tokenVersion; }

    /**
     * 设置 Token 版本号，null 安全（null 时设为 0）。
     *
     * @param tokenVersion Token 版本号
     */
    public void setTokenVersion(Integer tokenVersion) { this.tokenVersion = tokenVersion == null ? 0 : tokenVersion; }

    /**
     * 递增 Token 版本号 — 调用后所有旧 Token 立即失效。
     * 用于修改密码、登出、管理员封禁用户等场景。
     */
    public void incrementTokenVersion() { this.tokenVersion = getTokenVersion() + 1; }
}
