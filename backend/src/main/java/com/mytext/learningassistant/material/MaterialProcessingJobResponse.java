package com.mytext.learningassistant.material;

/**
 * 后台处理任务响应 DTO。
 */
public record MaterialProcessingJobResponse(
    Long id,
    Long materialId,
    String jobType,
    String status,
    Integer priority,
    Integer attemptCount,
    Integer maxAttempts,
    Integer progressPercent,
    String stage,
    String message,
    String errorMessage,
    String lockedBy,
    String lockedAt,
    String startedAt,
    String finishedAt,
    String createdAt,
    String updatedAt
) {
}
