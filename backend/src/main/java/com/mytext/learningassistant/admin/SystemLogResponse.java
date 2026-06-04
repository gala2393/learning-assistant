package com.mytext.learningassistant.admin;

/**
 * 管理后台 — 系统日志响应 DTO。
 * 展示一条管理员操作日志的完整信息，包含操作人的用户名。
 */
public record SystemLogResponse(
    Long id,              // 日志 ID
    Long actorUserId,     // 操作人用户 ID
    String actorUsername, // 操作人用户名（关联查询后填充）
    String action,        // 操作类型（如 UPDATE_USER_ROLE）
    String targetType,    // 操作对象类型（如 USER）
    Long targetId,        // 操作对象 ID
    String detail,        // 操作详情
    String createdAt      // 操作时间
) {
}
