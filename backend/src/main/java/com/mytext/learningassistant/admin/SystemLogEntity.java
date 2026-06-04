package com.mytext.learningassistant.admin;

import java.time.LocalDateTime;
import java.time.ZoneId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 系统日志实体 — 对应 system_log 表，记录管理员的操作日志。
 *
 * 每当管理员执行敏感操作（修改用户角色、禁用用户、修改资料状态等）时，
 * AdminService 会自动写入一条日志记录，便于审计和追溯。
 */
@Entity
@Table(name = "system_log")
public class SystemLogEntity {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人的用户 ID */
    @Column(name = "actor_user_id", nullable = false)
    private Long actorUserId;

    /** 操作类型，如 UPDATE_USER_ROLE、UPDATE_USER_STATUS、UPDATE_MATERIAL_STATUS */
    @Column(nullable = false, length = 64)
    private String action;

    /** 操作对象类型，如 USER、MATERIAL */
    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;

    /** 操作对象的 ID */
    @Column(name = "target_id")
    private Long targetId;

    /** 操作详情（如 role=ADMIN、status=DISABLED 等键值对） */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 操作时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(BEIJING_ZONE);
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
