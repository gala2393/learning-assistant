package com.mytext.learningassistant.rag;

public record RagHistoryMessageResponse(
    Long id,
    String role,
    String text
) {
}
