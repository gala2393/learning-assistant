package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户名密码登录请求记录类
 * 用于接收用户通过用户名和密码登录的请求参数
 * 使用 Jakarta Validation 进行参数校验
 */
public record LoginRequest(
    /** 用户登录名，必填，不能为空 */
    @NotBlank(message = "用户名不能为空")
    String username,

    /** 登录密码，必填，长度需在8到64位之间 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在8到64位之间")
    String password
) {
}
