package com.mytext.learningassistant.auth;

public record LoginCaptchaResponse(
    String challengeId,
    String imageDataUrl,
    long expiresInSeconds
) {
}
