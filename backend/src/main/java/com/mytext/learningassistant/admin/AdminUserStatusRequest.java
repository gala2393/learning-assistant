package com.mytext.learningassistant.admin;

/**
 * 管理后台 — 用户状态修改请求。
 * 管理员可以禁用或启用用户账号。禁用后用户无法登录。
 */
public record AdminUserStatusRequest(
    String status  // 目标状态："ACTIVE"（启用）或 "DISABLED"（禁用）
) {
}
