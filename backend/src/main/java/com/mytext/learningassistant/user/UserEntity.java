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
 * 用户实体 — 对应数据库中的 sys_user 表，存储所有用户的账号信息。
 * <p>
 * JPA 会自动把这个类的字段映射到数据库列。字段名用驼峰（如 passwordHash），
 * 数据库用下划线（password_hash），JPA 自动转换。
 * <p>
 * {@code @PrePersist}：数据插入前自动设置创建时间、默认角色和状态。
 * {@code @PreUpdate}：数据更新前自动刷新更新时间。
 */
@Entity                                       // 声明为 JPA 实体
@Table(name = "sys_user")                    // 映射到 sys_user 表
public class UserEntity {

    /** 北京时区（Asia/Shanghai），所有时间字段使用此时区 */
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    @Id                                                          // 主键
    @GeneratedValue(strategy = GenerationType.IDENTITY)           // 自增ID（由数据库生成）
    private Long id;

    @Column(nullable = false, unique = true, length = 64)        // 不可为空、唯一、最长64字符
    private String username;                                      // 登录用户名

    @Column(unique = true, length = 128)                         // 唯一、最长128字符
    private String email;                                         // 邮箱（用于验证码登录）

    @Column(name = "password_hash", nullable = false, length = 128)
    private String passwordHash;                                   // 加密后的密码，不存明文

    @Column(nullable = false, length = 64)
    private String nickname;                                       // 显示昵称

    @Column(name = "avatar", columnDefinition = "TEXT")            // TEXT 类型，无长度限制
    private String avatar;                                         // 头像（preset:xxx 或 data:image Base64）

    @Enumerated(EnumType.STRING)                                   // 存储为字符串（USER/ADMIN）
    @Column(nullable = false, length = 20)
    private UserRole role;                                         // 角色：USER 或 ADMIN

    @Enumerated(EnumType.STRING)                                   // 存储为字符串（ACTIVE/DISABLED）
    @Column(nullable = false, length = 20)
    private UserStatus status;                                     // 状态：ACTIVE 或 DISABLED

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;                               // 创建时间（北京时间）

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;                               // 最后更新时间

    /**
     * 插入前自动执行：设置创建时间、更新时间、默认角色和状态。
     * JPA 在调用 EntityManager.persist() 之前触发此方法。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(BEIJING_ZONE);
        if (createdAt == null) {
            createdAt = now;  // 只有未设置时自动填（允许手动指定）
        }
        updatedAt = now;
        if (role == null) {
            role = UserRole.USER;       // 默认普通用户
        }
        if (status == null) {
            status = UserStatus.ACTIVE;  // 默认正常状态
        }
    }

    /** 更新前自动执行：刷新更新时间 */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(BEIJING_ZONE);
    }

    // ============ Getter / Setter ============

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
}
