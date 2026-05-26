package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
    @NotBlank(message = "请输入邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过128个字符")
    String email,

    @NotBlank(message = "请输入验证码")
    @Pattern(regexp = "\\d{6}", message = "请输入6位数字验证码")
    String code,

    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在8到64位之间")
    String newPassword,

    @NotBlank(message = "确认密码不能为空")
    String confirmPassword
) {
}
