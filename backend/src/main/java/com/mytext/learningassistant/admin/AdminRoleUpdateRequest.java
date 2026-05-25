package com.mytext.learningassistant.admin;

import jakarta.validation.constraints.NotBlank;

public record AdminRoleUpdateRequest(
    @NotBlank(message = "角色不能为空")
    String role
) {
}
