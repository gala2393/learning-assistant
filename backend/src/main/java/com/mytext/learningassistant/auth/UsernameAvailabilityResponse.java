package com.mytext.learningassistant.auth;

/**
 * 用户名可用性响应记录类
 * 用于返回用户名查重的结果信息
 * 帮助前端实时提示用户所选用户名是否可用
 */
public record UsernameAvailabilityResponse(
    /** 查询的用户名 */
    String username,

    /** 是否可用，true表示用户名未被占用可以使用，false表示已被占用 */
    boolean available,

    /** 提示信息，描述用户名可用性状态的具体说明 */
    String message
) {
}
