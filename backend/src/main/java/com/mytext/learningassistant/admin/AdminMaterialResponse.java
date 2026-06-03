package com.mytext.learningassistant.admin;

public record AdminMaterialResponse(
    Long id,
    Long ownerId,
    String ownerUsername,
    String title,
    String sourceType,
    String originalName,
    String sourceUrl,
    Long fileSize,
    String parseStatus,
    Integer parseProgressPercent,
    String parseStage,
    String parseMessage,
    String summaryStatus,
    Integer chunkCount,
    String createdAt,
    String updatedAt
) {
}
