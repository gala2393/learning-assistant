package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 邮箱验证码请求记录类
 * 用于接收前端发送验证码的请求参数
 * 包含邮箱地址和邮件服务商选择
 * 使用 Jakarta Validation 进行参数校验
 */
public record EmailCodeRequest(
    /** 邮箱地址，必填，需符合邮箱格式，最大长度64字符 */
    @NotBlank(message = "请输入邮箱")
    @Email(message = "邮箱格式不正确")
    @Size(max = 64, message = "邮箱长度不能超过64个字符")
    String email,

    /** 邮件服务商标识，可选值：qq、netease、163 */
    @Pattern(regexp = "qq|netease|163", message = "验证码通道不正确")
    String provider
) {
}
