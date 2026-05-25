package com.mytext.learningassistant.rag;

public record RagSummaryResponse(
    Long summaryId,
    Long materialId,
    String materialTitle,
    String summary,
    String summaryType,
    String modelName,
    Integer sourceCount,
    String createdAt
) {
}
