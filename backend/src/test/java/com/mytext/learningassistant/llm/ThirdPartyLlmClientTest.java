package com.mytext.learningassistant.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

/**
 * 第三方 LLM 客户端单元测试。
 * <p>
 * 覆盖范围：通用聊天提示词选择、模型身份问答的本地短路处理、
 * 历史消息中身份问答对当前问题的影响、资料问答 RAG 提示词构建、
 * 编程题目代码优先约束、查询扩展（expandQuery）解析、HyDE 假设性回答生成。
 * <p>
 * 使用内部 CapturingLlmClient 捕获实际传入的 systemPrompt 和 userPrompt，
 * 验证提示词构建逻辑的正确性，不调用真实 LLM 服务。
 */
class ThirdPartyLlmClientTest {

    /**
     * 测试场景：通用聊天模式（isGeneralChat=true）且没有临时资料上下文时使用快速提示词。
     * 预期结果：userPrompt 包含用户问题但不包含资料摘要；
     *           systemPrompt 包含"高质量 AI 助手"和"不编造事实"，但不包含"学习助手"。
     */
    @Test
    void generalChatUsesFastPromptWithoutMaterialContext() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        Optional<LlmCompletion> completion = client.answer("Explain spaced repetition", List.of(), List.of(), true);

        assertTrue(completion.isPresent());
        assertTrue(llmClient.userPrompt.contains("Explain spaced repetition"));
        assertFalse(llmClient.userPrompt.contains("unused excerpt"));
        assertTrue(llmClient.systemPrompt.contains("高质量 AI 助手"));
        assertTrue(llmClient.systemPrompt.contains("不编造事实"));
        assertTrue(llmClient.systemPrompt.contains("代码任务规则"));
        assertFalse(llmClient.systemPrompt.contains("学习助手"));
        assertEquals(List.of(), llmClient.images);
    }

    /**
     * 测试场景：用户询问"你是什么大模型"等模型身份问题。
     * 预期结果：直接返回配置的模型标签（如"GPT5.5模型"），不调用 LLM 服务（chatCalls=0, streamCalls=0）。
     *           同步和流式接口行为一致。
     */
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
    void ordinaryModelQuestionsDoNotTriggerIdentityAnswer() {
        CapturingLlmClient llmClient = new CapturingLlmClient("normal model answer");
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        Optional<LlmCompletion> completion = client.answer(1L, "当前这个分类模型应该怎么使用？", List.of(), List.of(), true, "STUDY");

        assertTrue(completion.isPresent());
        assertEquals("normal model answer", completion.get().content());
        assertEquals(1, llmClient.chatCalls);
        assertFalse(client.isModelIdentityQuestion("当前这个分类模型应该怎么使用？"));
        assertFalse(client.isModelIdentityQuestion("请解释一下 Transformer 模型的注意力机制"));
        assertTrue(client.isModelIdentityQuestion("你是什么 AI？"));
    }

    /**
     * 测试场景：历史对话中包含模型身份问答，但当前问题为其他内容。
     * 预期结果：历史中的身份问答不影响当前问题处理，正常调用 LLM 服务（chatCalls=1）。
     */
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

    /**
     * 测试场景：资料问答模式（isGeneralChat=false）使用 RAG 提示词。
     * 预期结果：userPrompt 包含资料摘要和"可参考资料片段"提示；
     *           systemPrompt 包含"资料问答规则"，不包含"先给出直接结论"。
     */
    @Test
    void materialChatKeepsRagPrompt() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        client.answer("What does this section say?", List.of("source excerpt A"), List.of(), false);

        assertTrue(llmClient.userPrompt.contains("source excerpt A"));
        assertTrue(llmClient.systemPrompt.contains("资料问答规则"));
        assertTrue(llmClient.userPrompt.contains("可参考资料片段"));
        assertFalse(llmClient.userPrompt.contains("先给出直接结论"));
        assertFalse(llmClient.systemPrompt.contains("语言简洁专业..."));
    }

    /**
     * 测试场景：用户提问包含编程相关关键词（如"代码"、"Java"）。
     * 预期结果：systemPrompt 包含代码优先约束（"只输出完整代码"、"默认使用 Java"等规则）。
     */
    @Test
    void programmingQuestionsReceiveCodeFirstConstraints() {
        CapturingLlmClient llmClient = new CapturingLlmClient();
        ThirdPartyLlmClient client = new ThirdPartyLlmClient(llmClient);

        client.answer("用 Java 写两数之和，只要代码", List.of(), List.of(), true);

        assertTrue(llmClient.systemPrompt.contains("没有指定语言时，默认使用 Java"));
        assertTrue(llmClient.systemPrompt.contains("只输出完整代码"));
        assertTrue(llmClient.systemPrompt.contains("输入输出格式或框架"));
        assertTrue(llmClient.userPrompt.contains("用 Java 写两数之和，只要代码"));
    }

    /**
     * 测试场景：expandQuery 解析 LLM 返回的编号列表和 HyDE 行。
     * 预期结果：成功解析出编号项和 HyDE 假设性回答，systemPrompt 包含 "RAG" 指示。
     */
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

    /**
     * 测试场景：generateHydeAnswer 使用专用 HyDE 提示词并规范化输出。
     * 预期结果：去掉 "HyDE:" 前缀后的文本作为假设性回答返回；
     *           systemPrompt 包含 "HyDE"，userPrompt 包含原始问题。
     */
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

    // ========== 内部测试替身 ==========

    /**
     * 捕获型 LLM 客户端实现，用于记录传入的提示词和调用次数，
     * 以便在测试中断言提示词构建逻辑的正确性。
     */
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
