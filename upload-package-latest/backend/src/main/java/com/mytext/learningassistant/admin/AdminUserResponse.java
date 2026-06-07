package com.mytext.learningassistant.admin;

/**
 * 管理后台 — 用户信息响应 DTO。
 * 管理员查看用户列表时使用，包含用户的基本信息和账号状态。
 */
public record AdminUserResponse(
    Long id,           // 用户 ID
    String username,   // 用户名
    String nickname,   // 昵称
    String role,       // 角色（USER/ADMIN）
    String status,     // 状态（ACTIVE/DISABLED）
    String createdAt,  // 注册时间
    String updatedAt   // 最后更新时间
) {
}
