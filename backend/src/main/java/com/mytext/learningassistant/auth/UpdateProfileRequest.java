package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Size;

/**
 * 更新用户资料请求记录类
 * 用于接收用户修改个人资料的请求参数
 * 支持修改昵称和头像信息
 * 使用 Jakarta Validation 进行参数校验
 */
public record UpdateProfileRequest(
    /** 用户昵称，可选，最大长度64字符 */
    @Size(max = 64, message = "昵称长度不能超过64位")
    String nickname,

    /** 用户头像，可选，存储Base64编码的图片数据，最大长度262144字符（约256KB） */
    @Size(max = 262144, message = "头像数据过大")
    String avatar
) {
}
