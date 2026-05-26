package com.mytext.learningassistant.llm;

public record LlmStatusResponse(
    boolean enabled,
    boolean configured,
    String message
) {
}
