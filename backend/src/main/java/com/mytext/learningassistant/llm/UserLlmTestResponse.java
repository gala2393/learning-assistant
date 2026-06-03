package com.mytext.learningassistant.llm;

public record UserLlmTestResponse(
    boolean ok,
    String message,
    String model
) {
}
