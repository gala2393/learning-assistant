package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailCodeRequest(
    @NotBlank(message = "请输入邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过64个字符")
    String email,

    @Pattern(regexp = "qq|netease|163", message = "验证码通道不正确")
    String provider
) {
}
