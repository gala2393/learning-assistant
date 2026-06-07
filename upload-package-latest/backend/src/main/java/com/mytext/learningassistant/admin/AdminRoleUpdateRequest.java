package com.mytext.learningassistant.admin;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理后台 — 用户角色修改请求。
 * 管理员可以将用户的角色在 USER（普通用户）和 ADMIN（管理员）之间切换。
 * 至少保留一个管理员，不能把自己降级为普通用户。
 */
public record AdminRoleUpdateRequest(
    @NotBlank(message = "角色不能为空")
    String role  // 目标角色："USER" 或 "ADMIN"
) {
}
