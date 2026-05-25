package com.mytext.learningassistant.material;

public record MaterialResponse(
    Long id,
    String title,
    String sourceType,
    String originalName,
    String sourceUrl,
    Long fileSize,
    String parseStatus,
    String summaryStatus,
    String previewStatus,
    String previewError,
    Integer pageCount,
    Integer chunkCount,
    String createdAt,
    String updatedAt
) {
}
