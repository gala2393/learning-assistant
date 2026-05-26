package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Size;

public record RegisterRequest(
    @Size(max = 128, message = "邮箱长度不能超过128个字符")
    String email,

    @Size(min = 3, max = 64, message = "用户名长度需要在3到64位之间")
    String username,

    @Size(min = 8, max = 64, message = "密码长度需要在8到64位之间")
    String password,

    String confirmPassword,

    String code,

    String nickname
) {
}
