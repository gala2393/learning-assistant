package com.mytext.learningassistant.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

class ThirdPartyLlmClientTest {

    @Test
    void generalChatUsesShortFastPrompt() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        Optional<LlmCompletion> completion = client.answer("解释一下费曼学习法", List.of("unused excerpt"), List.of(), true);

        assertTrue(completion.isPresent());
        assertTrue(llmClient.systemPrompt.contains("高效的中文学习助手"));
        assertTrue(llmClient.userPrompt.contains("解释一下费曼学习法"));
        assertFalse(llmClient.systemPrompt.contains("课程学习助手"));
        assertFalse(llmClient.userPrompt.contains("资料检索片段"));
        assertEquals(List.of(), llmClient.images);
    }

    @Test
    void materialChatKeepsRagPrompt() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        client.answer("这段讲什么", List.of("资料片段 A"), List.of(), false);

        assertTrue(llmClient.userPrompt.contains("资料检索片段"));
        assertTrue(llmClient.userPrompt.contains("资料片段 A"));
    }

    private static class CapturingLlmClient implements LlmClient {
        private String systemPrompt = "";
        private String userPrompt = "";
        private List<LlmImage> images = List.of();

        @Override
        public Optional<LlmResult> chat(String systemPrompt, String userPrompt, List<LlmImage> images) {
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            this.images = images;
            return Optional.of(new LlmResult("ok", "fake-model"));
        }

        @Override
        public String chatStream(String systemPrompt, String userPrompt, List<LlmImage> images, Consumer<String> onChunk) {
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            this.images = images;
            onChunk.accept("ok");
            return "ok";
        }
    }
}
