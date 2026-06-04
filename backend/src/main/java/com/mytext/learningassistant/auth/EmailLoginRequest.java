package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 邮箱登录请求记录类
 * 用于接收用户通过邮箱验证码登录的请求参数
 * 使用 Jakarta Validation 进行参数校验
 */
public record EmailLoginRequest(
    /** 邮箱地址，必填，需符合邮箱格式，最大长度64字符 */
    @NotBlank(message = "请输入邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过64个字符")
    String email,

    /** 6位数字验证码，必填，格式校验为6位纯数字 */
    @NotBlank(message = "请输入验证码")
    @Pattern(regexp = "\\d{6}", message = "验证码必须是6位数字")
    String code
) {
}
