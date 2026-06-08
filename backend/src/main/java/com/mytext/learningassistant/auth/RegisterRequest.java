package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Size;

/**
 * 用户注册请求记录类
 * 用于接收新用户注册的请求参数
 * 支持邮箱注册和用户名注册两种方式
 * 使用 Jakarta Validation 进行参数校验
 */
public record RegisterRequest(
    /** 邮箱地址，可选，最大长度128字符（用于邮箱注册方式） */
    @Size(max = 128, message = "邮箱长度不能超过128个字符")
    String email,

    /** 用户登录名，可选，长度需在1到64位之间（用于用户名注册方式） */
    @Size(min = 1, max = 64, message = "用户名长度需要在1到64位之间")
    String username,

    /** 注册密码，可选，长度需在8到64位之间 */
    @Size(min = 8, max = 64, message = "密码长度需要在8到64位之间")
    String password,

    /** 确认密码，需与password字段一致（用于前端确认输入） */
    String confirmPassword,

    /** 邮箱验证码，用于邮箱注册方式的身份验证 */
    String code,

    /** 用户昵称，可选，用于界面显示的友好名称 */
    String nickname
) {
}
