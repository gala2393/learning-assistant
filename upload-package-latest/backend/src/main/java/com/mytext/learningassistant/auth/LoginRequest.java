package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 用户名密码登录请求记录类 — 用于接收前端发送的登录请求参数。
 * <p>
 * 使用 Java 的 record 特性自动生成 getter、equals、hashCode 和 toString 方法。
 * 使用 Jakarta Validation 注解进行参数自动校验，校验失败时由 {@link com.mytext.learningassistant.common.GlobalExceptionHandler}
 * 捕获并返回友好的错误信息。
 *
 * @param username           用户登录名（用户名或邮箱），必填，不能为空
 * @param password           登录密码，必填，长度需在 8 到 64 位之间
 * @param captchaChallengeId 验证码挑战 ID（当登录失败次数过多时，前端需传入）
 * @param captchaCode        验证码答案（当登录失败次数过多时，前端需传入）
 */
public record LoginRequest(
    /** 用户登录名（用户名或邮箱），必填 */
    @NotBlank(message = "用户名不能为空")
    String username,

    /** 登录密码，长度需在 8 到 64 位之间 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 8, max = 64, message = "密码长度需在8到64位之间")
    String password,

    /** 验证码挑战 ID，可选（登录失败次数过多时需要） */
    String captchaChallengeId,

    /** 验证码答案，可选（登录失败次数过多时需要） */
    String captchaCode
) {
}
