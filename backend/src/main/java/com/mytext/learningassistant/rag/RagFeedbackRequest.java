package com.mytext.learningassistant.rag;

public record RagFeedbackRequest(
    Integer rating,
    String comment
) {
}
