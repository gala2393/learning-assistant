package com.mytext.learningassistant.auth;

/**
 * 登录响应记录类
 * 用于封装登录成功后返回给前端的响应数据
 * 包含身份验证令牌和用户详细信息
 */
public record LoginResponse(
    /** JWT身份验证令牌，后续请求需要携带此令牌进行身份验证 */
    String token,

    /** 用户详细信息，包含用户资料、权限等数据 */
    AuthUserResponse user
) {
}
