package com.mytext.learningassistant.admin;

public record SystemLogResponse(
    Long id,
    Long actorUserId,
    String actorUsername,
    String action,
    String targetType,
    Long targetId,
    String detail,
    String createdAt
) {
}
