package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePasswordRequest(
    @NotBlank(message = "当前密码不能为空")
    String currentPassword,

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在8到64位之间")
    String newPassword,

    @NotBlank(message = "确认密码不能为空")
    String confirmPassword
) {
}
