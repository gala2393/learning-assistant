package com.mytext.learningassistant.auth;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
    @Size(max = 64, message = "昵称长度不能超过64位")
    String nickname,

    @Size(max = 262144, message = "头像数据过大")
    String avatar
) {
}
