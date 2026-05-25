package com.mytext.learningassistant.material;

public record MaterialUploadSessionResponse(
    String sessionId,
    String clientUploadId,
    Long materialId,
    String title,
    String originalName,
    String sourceType,
    String sourceUrl,
    Long fileSize,
    Integer chunkSize,
    Integer totalChunks,
    Integer uploadedChunks,
    String status,
    String errorMessage,
    String createdAt,
    String updatedAt
) {
}
