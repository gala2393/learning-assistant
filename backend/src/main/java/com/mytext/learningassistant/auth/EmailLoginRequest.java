package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record EmailLoginRequest(
    @NotBlank(message = "请输入邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过64个字符")
    String email,

    @NotBlank(message = "请输入验证码")
    @Pattern(regexp = "\\d{6}", message = "验证码必须是6位数字")
    String code
) {
}
