package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 重置密码请求记录类
 * 用于接收用户通过邮箱验证码重置密码的请求参数
 * 验证流程：发送邮箱验证码 -> 用户提交验证码和新密码 -> 完成密码重置
 * 使用 Jakarta Validation 进行参数校验
 */
public record ResetPasswordRequest(
    /** 邮箱地址，必填，需符合邮箱格式，最大长度128字符 */
    @NotBlank(message = "请输入邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱长度不能超过128个字符")
    String email,

    /** 6位数字验证码，必填，格式校验为6位纯数字 */
    @NotBlank(message = "请输入验证码")
    @Pattern(regexp = "\\d{6}", message = "请输入6位数字验证码")
    String code,

    /** 新密码，必填，长度需在8到64位之间 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在8到64位之间")
    String newPassword,

    /** 确认新密码，必填，需与newPassword字段一致 */
    @NotBlank(message = "确认密码不能为空")
    String confirmPassword
) {
}
