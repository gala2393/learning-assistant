package com.mytext.learningassistant.admin;

public record AdminUserResponse(
    Long id,
    String username,
    String nickname,
    String role,
    String status,
    String createdAt,
    String updatedAt
) {
}
