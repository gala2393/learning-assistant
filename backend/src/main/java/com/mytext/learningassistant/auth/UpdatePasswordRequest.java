package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求记录类
 * 用于接收已登录用户修改密码的请求参数
 * 需要用户提供当前密码进行身份验证
 * 使用 Jakarta Validation 进行参数校验
 */
public record UpdatePasswordRequest(
    /** 当前密码，必填，用于验证用户身份 */
    @NotBlank(message = "当前密码不能为空")
    String currentPassword,

    /** 新密码，必填，长度需在8到64位之间 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在8到64位之间")
    String newPassword,

    /** 确认新密码，必填，需与newPassword字段一致 */
    @NotBlank(message = "确认密码不能为空")
    String confirmPassword
) {
}
