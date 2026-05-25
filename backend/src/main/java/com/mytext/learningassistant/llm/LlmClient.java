package com.mytext.learningassistant.llm;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public interface LlmClient {

    Optional<LlmResult> chat(String systemPrompt, String userPrompt, List<LlmImage> images);

    default Optional<LlmResult> chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, List.of());
    }

    String chatStream(String systemPrompt, String userPrompt, List<LlmImage> images, Consumer<String> onChunk);
}
