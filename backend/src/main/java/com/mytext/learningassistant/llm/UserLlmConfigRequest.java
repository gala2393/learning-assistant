package com.mytext.learningassistant.llm;

public record UserLlmConfigRequest(
    Long id,
    boolean enabled,
    String displayName,
    String baseUrl,
    String apiKey,
    String model
) {
}
