package com.mytext.learningassistant.material;

public record MaterialDetailResponse(
    Long id,
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
    String previewStatus,
    String previewError,
    Integer pageCount,
    Integer chunkCount,
    String createdAt,
    String updatedAt
) {
}
