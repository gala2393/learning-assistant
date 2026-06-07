package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginCaptchaRequest(
    @NotBlank(message = "请输入用户名或邮箱")
    String username
) {
}
