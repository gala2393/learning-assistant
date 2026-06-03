package com.mytext.learningassistant.rag;

public record RagFeedbackResponse(
    Long id,
    Long questionId,
    Integer rating,
    String comment,
    String updatedAt
) {
}
