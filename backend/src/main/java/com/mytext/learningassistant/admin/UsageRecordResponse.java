package com.mytext.learningassistant.admin;

public record UsageRecordResponse(
    Long id,
    Long userId,
    String username,
    String action,
    String targetType,
    Long targetId,
    String modelName,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    String detail,
    String createdAt
) {
}
