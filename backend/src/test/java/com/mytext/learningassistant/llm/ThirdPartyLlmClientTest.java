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
    void generalChatUsesFastPromptWithoutMaterialContext() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        Optional<LlmCompletion> completion = client.answer("Explain spaced repetition", List.of("unused excerpt"), List.of(), true);

        assertTrue(completion.isPresent());
        assertTrue(llmClient.userPrompt.contains("Explain spaced repetition"));
        assertFalse(llmClient.userPrompt.contains("unused excerpt"));
        assertEquals(List.of(), llmClient.images);
    }

    @Test
    void modelIdentityQuestionUsesConfiguredLabelWithoutCallingModel() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        Optional<LlmCompletion> completion = client.answer(1L, "\u4f60\u662f\u4ec0\u4e48\u5927\u6a21\u578b", List.of(), List.of(), true, "STUDY");
        StringBuilder streamed = new StringBuilder();
        String streamAnswer = client.answerStream(1L, "\u5f53\u524d\u8c03\u7528\u7684\u5927\u6a21\u578b\u662f\u4ec0\u4e48", List.of(), List.of(), streamed::append, true, "STUDY");

        assertTrue(completion.isPresent());
        assertEquals("\u5f53\u524d\u4f7f\u7528\u7684\u662f GPT5.5\u6a21\u578b\u3002", completion.get().content());
        assertEquals("\u5f53\u524d\u4f7f\u7528\u7684\u662f GPT5.5\u6a21\u578b\u3002", streamAnswer);
        assertEquals("\u5f53\u524d\u4f7f\u7528\u7684\u662f GPT5.5\u6a21\u578b\u3002", streamed.toString());
        assertEquals(0, llmClient.chatCalls);
        assertEquals(0, llmClient.streamCalls);
    }

    @Test
    void modelIdentityQuestionInHistoryDoesNotAffectCurrentQuestion() {
        CapturingLlmClient llmClient = new CapturingLlmClient("normal answer");
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);
        String promptWithHistory = """
            history:
            user: \u4f60\u662f\u4ec0\u4e48\u6a21\u578b
            assistant: \u5f53\u524d\u4f7f\u7528\u7684\u662f GPT5.5\u6a21\u578b\u3002

            current: \u4eca\u5929\u662f\u51e0\u53f7
            """;

        Optional<LlmCompletion> completion = client.answer(1L, promptWithHistory, List.of(), List.of(), true, "STUDY");

        assertTrue(completion.isPresent());
        assertEquals("normal answer", completion.get().content());
        assertEquals(1, llmClient.chatCalls);
    }

    @Test
    void materialChatKeepsRagPrompt() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        client.answer("What does this section say?", List.of("source excerpt A"), List.of(), false);

        assertTrue(llmClient.userPrompt.contains("source excerpt A"));
    }

    @Test
    void expandQueryParsesNumberedAndHydeLines() {
        CapturingLlmClient llmClient = new CapturingLlmClient("""
            1. alpha retrieval marker
            HyDE: a short hypothetical answer with marker context
            - alpha retrieval marker
            """);
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        Optional<List<String>> expansions = client.expandQuery("Can you recover the clue?");

        assertTrue(expansions.isPresent());
        assertEquals(List.of(
            "alpha retrieval marker",
            "a short hypothetical answer with marker context"
        ), expansions.get());
        assertTrue(llmClient.systemPrompt.contains("RAG"));
    }

    @Test
    void generateHydeAnswerUsesDedicatedPromptAndNormalizesOutput() {
        CapturingLlmClient llmClient = new CapturingLlmClient("HyDE: database indexes accelerate lookup by narrowing scanned rows.");
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        Optional<String> hyde = client.generateHydeAnswer("How do indexes help lookup?");

        assertTrue(hyde.isPresent());
        assertEquals("database indexes accelerate lookup by narrowing scanned rows.", hyde.get());
        assertTrue(llmClient.systemPrompt.contains("HyDE"));
        assertTrue(llmClient.userPrompt.contains("How do indexes help lookup?"));
    }

    private static class CapturingLlmClient implements LlmClient {
        private final String content;
        private String systemPrompt = "";
        private String userPrompt = "";
        private List<LlmImage> images = List.of();
        private int chatCalls = 0;
        private int streamCalls = 0;

        private CapturingLlmClient() {
            this("ok");
        }

        private CapturingLlmClient(String content) {
            this.content = content;
        }

        @Override
        public Optional<LlmResult> chat(String systemPrompt, String userPrompt, List<LlmImage> images) {
            chatCalls++;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            this.images = images;
            return Optional.of(new LlmResult(content, "fake-model"));
        }

        @Override
        public String chatStream(String systemPrompt, String userPrompt, List<LlmImage> images, Consumer<String> onChunk) {
            streamCalls++;
            this.systemPrompt = systemPrompt;
            this.userPrompt = userPrompt;
            this.images = images;
            onChunk.accept("ok");
            return "ok";
        }
    }
}
