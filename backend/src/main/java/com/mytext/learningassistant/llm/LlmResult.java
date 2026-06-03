package com.mytext.learningassistant.llm;

public record LlmResult(
    String content,
    String modelName,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens
) {

    public LlmResult(String content, String modelName) {
        this(content, modelName, null, null, null);
    }
}
