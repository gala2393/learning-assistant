package com.mytext.learningassistant.auth;

public record LoginResponse(
    String token,
    AuthUserResponse user
) {
}
