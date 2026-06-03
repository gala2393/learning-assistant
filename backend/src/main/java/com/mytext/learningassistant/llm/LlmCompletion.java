package com.mytext.learningassistant.llm;

public record LlmCompletion(
    String content,
    String modelName,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    boolean customModel
) {

    public LlmCompletion(String content, String modelName) {
        this(content, modelName, null, null, null, false);
    }

    public LlmCompletion(String content, String modelName, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(content, modelName, promptTokens, completionTokens, totalTokens, false);
    }
}
