package com.mytext.learningassistant.llm;

public record UserLlmConfigItemResponse(
    Long id,
    String displayName,
    String baseUrl,
    String model,
    boolean hasApiKey,
    boolean active
) {
}
