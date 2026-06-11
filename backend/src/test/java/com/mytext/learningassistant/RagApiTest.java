package com.mytext.learningassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mytext.learningassistant.embedding.EmbeddingClient;
import com.mytext.learningassistant.llm.LlmCompletion;
import com.mytext.learningassistant.llm.LlmProviderException;
import com.mytext.learningassistant.llm.ThirdPartyLlmClient;
import com.mytext.learningassistant.material.MaterialChunkRepository;
import com.mytext.learningassistant.material.TemporaryMaterialContextEntity;
import com.mytext.learningassistant.material.TemporaryMaterialContextRepository;
import com.mytext.learningassistant.rag.RagEvaluationSuiteScheduler;
import com.mytext.learningassistant.rag.RagQuestionRepository;
import com.mytext.learningassistant.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RagApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @MockitoBean
    private ThirdPartyLlmClient thirdPartyLlmClient;

    @Autowired
    private MaterialChunkRepository materialChunkRepository;

    @Autowired
    private RagQuestionRepository ragQuestionRepository;

    @Autowired
    private TemporaryMaterialContextRepository temporaryMaterialContextRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RagEvaluationSuiteScheduler ragEvaluationSuiteScheduler;

    @BeforeEach
    void useLocalFallbackByDefault() {
        when(embeddingClient.embedQuery(anyString())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean(), any())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.summarize(anyString(), anyList())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.summarizeStructured(anyString(), anyString(), anyList())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.summarizeStructured(anyString(), anyString(), anyList(), anyList())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.expandQuery(anyString())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.generateHydeAnswer(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void chatReturnsSourcesAndPersistsHistoryAndFavorite() throws Exception {
        String token = registerAndLogin(uniqueName("rag-user"));
        uploadMaterial(token);

        var chatResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "How does RAG retrieve relevant course chunks?"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.question").value("How does RAG retrieve relevant course chunks?"))
            .andExpect(jsonPath("$.data.answer").isNotEmpty())
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("Course RAG Material")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("页")))
            .andExpect(jsonPath("$.data.sources[0].materialTitle").value("Course RAG Material"))
            .andExpect(jsonPath("$.data.sources[0].chunkId").isNumber())
            .andReturn();

        String chatBody = chatResult.getResponse().getContentAsString();
        Long questionId = extractLong(chatBody, "questionId");

        mockMvc.perform(get("/api/rag/history")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].id").value(questionId.intValue()))
            .andExpect(jsonPath("$.data[0].question").value("How does RAG retrieve relevant course chunks?"));

        mockMvc.perform(get("/api/rag/history/" + questionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").value(questionId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].materialTitle").value("Course RAG Material"));

        mockMvc.perform(post("/api/favorites")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "questionId": %d
                    }
                    """.formatted(questionId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/favorites")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].questionId").value(questionId.intValue()))
            .andExpect(jsonPath("$.data[0].messages", hasSize(2)));
    }

    @Test
    void chatRejectsQuestionOverInputLimit() throws Exception {
        String token = registerAndLogin(uniqueName("rag-input-limit-user"));
        String longQuestion = "我".repeat(20001);

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "%s",
                      "mode": "GENERAL"
                    }
                    """.formatted(longQuestion)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("参数校验失败"))
            .andExpect(jsonPath("$.data.question").value("问题不能超过 20000 字"));
    }

    @Test
    void canSubmitAndUpdateRagFeedback() throws Exception {
        String token = registerAndLogin(uniqueName("rag-feedback-user"));
        uploadMaterial(token);

        Long questionId = createChatAndReturnQuestionId(token, "How does RAG retrieve relevant course chunks?");

        mockMvc.perform(patch("/api/rag/history/" + questionId + "/feedback")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "rating": 1,
                      "comment": "Relevant sources"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.questionId").value(questionId.intValue()))
            .andExpect(jsonPath("$.data.rating").value(1))
            .andExpect(jsonPath("$.data.comment").value("Relevant sources"));

        mockMvc.perform(patch("/api/rag/history/" + questionId + "/feedback")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "rating": -1,
                      "comment": "Missing detail"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.questionId").value(questionId.intValue()))
            .andExpect(jsonPath("$.data.rating").value(-1))
            .andExpect(jsonPath("$.data.comment").value("Missing detail"));
    }

    @Test
    void canEvaluateRagAnswerFaithfulnessAndContextRelevance() throws Exception {
        String token = registerAndLogin(uniqueName("rag-evaluation-user"));
        uploadMaterial(token);

        Long questionId = createChatAndReturnQuestionId(token, "How does RAG retrieve relevant course chunks?");

        var evaluationResult = mockMvc.perform(post("/api/rag/history/" + questionId + "/evaluation")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.questionId").value(questionId.intValue()))
            .andExpect(jsonPath("$.data.faithfulnessScore").isNumber())
            .andExpect(jsonPath("$.data.contextRelevanceScore").isNumber())
            .andExpect(jsonPath("$.data.overallScore").isNumber())
            .andExpect(jsonPath("$.data.verdict").isNotEmpty())
            .andExpect(jsonPath("$.data.evidence").value(org.hamcrest.Matchers.containsString("sources=")))
            .andReturn();
        Long evaluationId = extractLong(evaluationResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/rag/history/" + questionId + "/evaluation")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").value(evaluationId.intValue()))
            .andExpect(jsonPath("$.data.questionId").value(questionId.intValue()))
            .andExpect(jsonPath("$.data.evidence").isNotEmpty());
    }

    @Test
    void canRunOfflineRagEvaluationSuite() throws Exception {
        String token = registerAndLogin(uniqueName("rag-suite-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Offline Evaluation Material",
            "RAG retrieves relevant course chunks before answering student questions. "
                + "BM25 and vector search are different retrieval methods."
        );

        mockMvc.perform(post("/api/rag/evaluation-suite")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "cases": [
                        {
                          "question": "How does RAG answer student questions?",
                          "materialId": %d,
                          "expectedAnswerTerms": ["RAG", "student"],
                          "expectedSourceTerms": ["relevant course chunks"]
                        },
                        {
                          "question": "What retrieval methods are mentioned?",
                          "materialId": %d,
                          "expectedAnswerTerms": ["BM25", "vector search"],
                          "expectedSourceTerms": ["BM25", "vector search"]
                        }
                      ]
                    }
                    """.formatted(materialId, materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.totalCases").value(2))
            .andExpect(jsonPath("$.data.passedCases").value(2))
            .andExpect(jsonPath("$.data.passRate").value(1.0))
            .andExpect(jsonPath("$.data.averageOverallScore").isNumber())
            .andExpect(jsonPath("$.data.cases[0].questionId").isNumber())
            .andExpect(jsonPath("$.data.cases[0].expectedAnswerCoverage").value(1.0))
            .andExpect(jsonPath("$.data.cases[0].expectedSourceCoverage").value(1.0))
            .andExpect(jsonPath("$.data.cases[0].passed").value(true))
            .andExpect(jsonPath("$.data.cases[1].expectedAnswerCoverage").value(1.0))
            .andExpect(jsonPath("$.data.cases[1].expectedSourceCoverage").value(1.0))
            .andExpect(jsonPath("$.data.cases[1].missingAnswerTerms", hasSize(0)))
            .andExpect(jsonPath("$.data.cases[1].missingSourceTerms", hasSize(0)));
    }

    @Test
    void canPersistAndRunRagEvaluationSuite() throws Exception {
        String token = registerAndLogin(uniqueName("rag-persisted-suite-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Persisted Evaluation Material",
            "RAG retrieves relevant course chunks before answering student questions."
        );

        var saveResult = mockMvc.perform(post("/api/rag/evaluation-suites")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Core RAG Regression",
                      "description": "Saved regression cases",
                      "cases": [
                        {
                          "question": "How does RAG answer student questions?",
                          "materialId": %d,
                          "expectedAnswerTerms": ["RAG", "student"],
                          "expectedSourceTerms": ["relevant course chunks"]
                        }
                      ]
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.name").value("Core RAG Regression"))
            .andExpect(jsonPath("$.data.cases[0].question").value("How does RAG answer student questions?"))
            .andReturn();
        Long suiteId = extractLong(saveResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/rag/evaluation-suites")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].id").value(suiteId.intValue()))
            .andExpect(jsonPath("$.data[0].caseCount").value(1));

        var runResult = mockMvc.perform(post("/api/rag/evaluation-suites/" + suiteId + "/runs")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.suiteId").value(suiteId.intValue()))
            .andExpect(jsonPath("$.data.totalCases").value(1))
            .andExpect(jsonPath("$.data.passedCases").value(1))
            .andExpect(jsonPath("$.data.passRate").value(1.0))
            .andExpect(jsonPath("$.data.result.cases[0].passed").value(true))
            .andReturn();
        Long runId = extractLong(runResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/rag/evaluation-suites/" + suiteId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.latestRun.id").value(runId.intValue()))
            .andExpect(jsonPath("$.data.latestRun.result.totalCases").value(1));

        mockMvc.perform(get("/api/rag/evaluation-suites/" + suiteId + "/runs")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].id").value(runId.intValue()))
            .andExpect(jsonPath("$.data[0].result.passRate").value(1.0));
    }

    @Test
    void scheduledRagEvaluationSuiteRunsWhenDue() throws Exception {
        String token = registerAndLogin(uniqueName("rag-scheduled-suite-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Scheduled Evaluation Material",
            "RAG retrieves relevant course chunks before answering student questions."
        );

        var saveResult = mockMvc.perform(post("/api/rag/evaluation-suites")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Scheduled RAG Regression",
                      "description": "Runs on a cadence",
                      "cases": [
                        {
                          "question": "How does RAG answer student questions?",
                          "materialId": %d,
                          "expectedAnswerTerms": ["RAG", "student"],
                          "expectedSourceTerms": ["relevant course chunks"]
                        }
                      ]
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andReturn();
        Long suiteId = extractLong(saveResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(patch("/api/rag/evaluation-suites/" + suiteId + "/schedule")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "scheduled": true,
                      "intervalHours": 1
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.scheduled").value(true))
            .andExpect(jsonPath("$.data.scheduleIntervalHours").value(1))
            .andExpect(jsonPath("$.data.nextRunAt").isNotEmpty());

        int runCount = ragEvaluationSuiteScheduler.runDueSuitesOnce(LocalDateTime.now().plusHours(2));
        org.assertj.core.api.Assertions.assertThat(runCount).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/rag/evaluation-suites/" + suiteId + "/runs")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].totalCases").value(1))
            .andExpect(jsonPath("$.data[0].passedCases").value(1));

        mockMvc.perform(get("/api/rag/evaluation-suites/" + suiteId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.scheduled").value(true))
            .andExpect(jsonPath("$.data.latestRun.result.passRate").value(1.0))
            .andExpect(jsonPath("$.data.nextRunAt").isNotEmpty());
    }

    @Test
    void deletesHistoryQuestionWithSourcesAndFavorite() throws Exception {
        String token = registerAndLogin(uniqueName("rag-delete-history-user"));
        uploadMaterial(token);

        var chatResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "How does RAG retrieve relevant course chunks?"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();

        Long questionId = extractLong(chatResult.getResponse().getContentAsString(), "questionId");

        mockMvc.perform(post("/api/favorites")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "questionId": %d
                    }
                    """.formatted(questionId)))
            .andExpect(status().isOk());

        mockMvc.perform(delete("/api/rag/history/" + questionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/rag/history/" + questionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/rag/history")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/api/favorites")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void multipleQuestionsInSameConversationShowAsOneHistoryConversation() throws Exception {
        String token = registerAndLogin(uniqueName("rag-conversation-user"));

        var firstResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Hello",
                      "mode": "GENERAL"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conversationId").isNumber())
            .andReturn();

        Long conversationId = extractLong(firstResult.getResponse().getContentAsString(), "conversationId");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Who are you?",
                      "mode": "GENERAL",
                      "conversationId": %d
                    }
                    """.formatted(conversationId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conversationId").value(conversationId.intValue()));

        mockMvc.perform(get("/api/rag/history")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].conversationId").value(conversationId.intValue()))
            .andExpect(jsonPath("$.data[0].question").value("Who are you?"));

        mockMvc.perform(get("/api/rag/history/" + conversationId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conversationId").value(conversationId.intValue()))
            .andExpect(jsonPath("$.data.messages", hasSize(4)));
    }

    @Test
    void materialChatPersistsMaterialIdAndRestoresLatestConversation() throws Exception {
        String token = registerAndLogin(uniqueName("rag-material-history-user"));
        Long materialId = uploadMaterialAndReturnId(token, "Reader History Material", "RAG material history should restore the latest reader conversation.");

        var firstResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "What should the reader restore?",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.conversationId").isNumber())
            .andReturn();

        Long firstQuestionId = extractLong(firstResult.getResponse().getContentAsString(), "questionId");
        Long conversationId = extractLong(firstResult.getResponse().getContentAsString(), "conversationId");
        org.assertj.core.api.Assertions.assertThat(ragQuestionRepository.findById(firstQuestionId).orElseThrow().getMaterialId())
            .isEqualTo(materialId);

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Continue this reader conversation.",
                      "mode": "MATERIAL",
                      "materialId": %d,
                      "conversationId": %d
                    }
                    """.formatted(materialId, conversationId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.conversationId").value(conversationId.intValue()));

        mockMvc.perform(get("/api/rag/history/materials/" + materialId + "/latest")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.conversationId").value(conversationId.intValue()))
            .andExpect(jsonPath("$.data.messages", hasSize(4)))
            .andExpect(jsonPath("$.data.messages[2].text").value("Continue this reader conversation."));

        mockMvc.perform(get("/api/rag/history/materials/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].conversationId").value(conversationId.intValue()))
            .andExpect(jsonPath("$.data[0].question").value("Continue this reader conversation."));
    }

    @Test
    void streamMaterialChatPersistsMaterialHistoryForReaderRestore() throws Exception {
        when(thirdPartyLlmClient.effectiveModelName(any())).thenReturn("mock-model");
        when(thirdPartyLlmClient.answerStream(
            anyLong(),
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && !excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq("HOMEWORK")
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onChunk = invocation.getArgument(4);
            onChunk.accept("流式资料回答");
            return "流式资料回答";
        });
        String token = registerAndLogin(uniqueName("rag-stream-material-history-user"));
        Long materialId = uploadMaterialAndReturnId(token, "Stream Reader Material", "RAG stream reader history should be saved with material id.");
        Long chunkId = extractChunkIdContaining(
            mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "RAG stream reader history"
        );

        var started = mockMvc.perform(post("/api/rag/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "边读边问流式问题会保存吗？",
                      "mode": "MATERIAL",
                      "materialId": %d,
                      "chunkId": %d,
                      "answerStyle": "HOMEWORK"
                    }
                    """.formatted(materialId, chunkId)))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
            .andReturn();

        started.getAsyncResult(30_000);
        var result = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();
        Long questionId = extractLong(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "questionId");
        Long conversationId = extractLong(result.getResponse().getContentAsString(StandardCharsets.UTF_8), "conversationId");

        org.assertj.core.api.Assertions.assertThat(questionId).isPositive();
        org.assertj.core.api.Assertions.assertThat(conversationId).isPositive();
        org.assertj.core.api.Assertions.assertThat(ragQuestionRepository.findById(questionId).orElseThrow().getMaterialId())
            .isEqualTo(materialId);

        mockMvc.perform(get("/api/rag/history/materials/" + materialId + "/latest")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.conversationId").value(conversationId.intValue()))
            .andExpect(jsonPath("$.data.messages", hasSize(2)))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(chunkId.intValue()));
    }

    @Test
    void latestMaterialHistoryReturnsNullWhenMaterialHasNoChatHistory() throws Exception {
        String token = registerAndLogin(uniqueName("rag-empty-material-history-user"));
        Long materialId = uploadMaterialAndReturnId(token, "Empty Reader History Material", "This material has no reader question yet.");

        mockMvc.perform(get("/api/rag/history/materials/" + materialId + "/latest")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get("/api/rag/history/materials/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void materialHistoryFallsBackToSourceMaterialIdForOldQuestions() throws Exception {
        String token = registerAndLogin(uniqueName("rag-old-material-history-user"));
        Long materialId = uploadMaterialAndReturnId(token, "Old Reader History Material", "Old rows only know the material through their saved RAG sources.");

        var chatResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Restore old reader history by source.",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andReturn();

        Long questionId = extractLong(chatResult.getResponse().getContentAsString(), "questionId");
        ragQuestionRepository.findById(questionId).ifPresent(question -> {
            // 模拟迁移前旧数据：问答主表没有 material_id，但来源表仍保留资料归属。
            question.setMaterialId(null);
            ragQuestionRepository.save(question);
        });

        mockMvc.perform(get("/api/rag/history/materials/" + materialId + "/latest")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").value(questionId.intValue()))
            .andExpect(jsonPath("$.data.question").value("Restore old reader history by source."));

        mockMvc.perform(get("/api/rag/history/materials/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].id").value(questionId.intValue()));
    }

    @Test
    void clearsAllHistoryQuestionsWithSourcesAndFavorites() throws Exception {
        String token = registerAndLogin(uniqueName("rag-clear-history-user"));
        uploadMaterial(token);

        Long firstQuestionId = createChatAndReturnQuestionId(token, "How does RAG retrieve relevant course chunks?");
        Long secondQuestionId = createChatAndReturnQuestionId(token, "What course chunks does RAG retrieve?");

        mockMvc.perform(post("/api/favorites")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "questionId": %d
                    }
                    """.formatted(firstQuestionId)))
            .andExpect(status().isOk());

        mockMvc.perform(delete("/api/rag/history")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/rag/history/" + firstQuestionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/rag/history/" + secondQuestionId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/rag/history")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty());

        mockMvc.perform(get("/api/favorites")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void summarizeBuildsMaterialSummaryAndUpdatesStatus() throws Exception {
        String token = registerAndLogin(uniqueName("summary-user"));
        Long materialId = uploadMaterialAndReturnId(token);

        mockMvc.perform(post("/api/rag/summarize")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.materialId").value(materialId.intValue()))
            .andExpect(jsonPath("$.data.materialTitle").value("Course RAG Material"))
            .andExpect(jsonPath("$.data.summary").isNotEmpty())
            .andExpect(jsonPath("$.data.sourceCount").value(1));

        mockMvc.perform(get("/api/materials/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.summaryStatus").value("SUCCESS"));

        mockMvc.perform(get("/api/rag/summaries/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.materialId").value(materialId.intValue()))
            .andExpect(jsonPath("$.data.summary").isNotEmpty());
    }

    @Test
    void chatUsesMaterialSummaryAsHierarchicalSeedBeforeChunkRetrieval() throws Exception {
        when(thirdPartyLlmClient.summarizeStructured(anyString(), anyString(), anyList(), anyList()))
            .thenReturn(Optional.of(new LlmCompletion("""
                {
                  "summary": "AuroraGraph explains layered graph planning and retrieval routing.",
                  "sections": [
                    {
                      "title": "核心摘要",
                      "items": ["AuroraGraph explains layered graph planning and retrieval routing."]
                    }
                  ]
                }
                """, "mock-model")));
        String token = registerAndLogin(uniqueName("summary-seed-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Layered Planning Material",
            "The first chapter describes neutral planning workflows. "
                + "The second chapter describes general routing examples without naming the rare concept."
        );

        mockMvc.perform(post("/api/rag/summarize")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.summary").value(org.hamcrest.Matchers.containsString("AuroraGraph")));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "What does AuroraGraph explain?"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].materialId").value(materialId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].materialTitle").value("Layered Planning Material"));
    }

    @Test
    void summaryHistoryReturnsLatestVersionsForMaterial() throws Exception {
        String token = registerAndLogin(uniqueName("summary-history-user"));
        Long materialId = uploadMaterialAndReturnId(token);

        mockMvc.perform(post("/api/rag/summarize")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/rag/summarize")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/rag/summaries/" + materialId + "/history")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].materialId").value(materialId.intValue()))
            .andExpect(jsonPath("$.data[0].summary").isNotEmpty())
            .andExpect(jsonPath("$.data[0].createdAt").isNotEmpty());
    }

    @Test
    void chatUsesLocalFallbackWhenQuestionHasNoRelevantTokenOverlap() throws Exception {
        String token = registerAndLogin(uniqueName("rag-fallback-user"));
        uploadMaterial(token);

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Explain orbital mechanics for satellites."
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.question").value("Explain orbital mechanics for satellites."))
            .andExpect(jsonPath("$.data.answer").isNotEmpty())
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("当前资料里没有检索到足够依据")))
            .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    @Test
    void summarizeUsesLocalFallbackModelWithoutThirdPartyConfiguration() throws Exception {
        String token = registerAndLogin(uniqueName("summary-fallback-user"));
        Long materialId = uploadMaterialAndReturnId(token);

        mockMvc.perform(post("/api/rag/summarize")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.materialId").value(materialId.intValue()))
            .andExpect(jsonPath("$.data.summary").isNotEmpty())
            .andExpect(jsonPath("$.data.summaryType").value("GENERAL"))
            .andExpect(jsonPath("$.data.modelName").value("local-rag-demo"))
            .andExpect(jsonPath("$.data.sourceCount").value(1));
    }

    @Test
    void chatUsesThirdPartyAnswerWhenLlmClientSucceeds() throws Exception {
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean()))
            .thenReturn(Optional.of(new LlmCompletion("""
                **Provider answer**
                * First point
                - Second point
                """, "mock-model")));
        String token = registerAndLogin(uniqueName("rag-llm-user"));
        uploadMaterial(token);

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "How does RAG retrieve relevant course chunks?"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("Provider answer")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("First point")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("**Provider answer**")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("* First point")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("- Second point")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("资料依据")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("Course RAG Material")))
            .andExpect(jsonPath("$.data.sources[0].materialTitle").value("Course RAG Material"));

        verify(thirdPartyLlmClient).answer(
            org.mockito.ArgumentMatchers.eq("How does RAG retrieve relevant course chunks?"),
            org.mockito.ArgumentMatchers.argThat(excerpts ->
                excerpts != null
                    && !excerpts.isEmpty()
                    && excerpts.get(0).contains("Course RAG Material")
                    && excerpts.get(0).contains("原文：")
            ),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(false)
        );
    }

    @Test
    void summarizeUsesThirdPartySummaryWhenLlmClientSucceeds() throws Exception {
        when(thirdPartyLlmClient.summarizeStructured(anyString(), anyString(), anyList(), anyList()))
            .thenReturn(Optional.of(new LlmCompletion("""
                {
                  "summary": "Provider summary",
                  "sections": [
                    {
                      "title": "核心摘要",
                      "items": ["Provider summary"]
                    }
                  ]
                }
                """, "mock-model")));
        String token = registerAndLogin(uniqueName("summary-llm-user"));
        Long materialId = uploadMaterialAndReturnId(token);

        mockMvc.perform(post("/api/rag/summarize")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.summary").value("Provider summary"))
            .andExpect(jsonPath("$.data.modelName").value("mock-model"))
            .andExpect(jsonPath("$.data.sourceCount").value(1));
    }

    @Test
    void chatUsesThirdPartyAnswerWhenNoSourceMatchesAndMarksNoBookPage() throws Exception {
        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(false)
        )).thenReturn(Optional.of(new LlmCompletion("General AI answer", "mock-model")));
        String token = registerAndLogin(uniqueName("rag-no-source-ai-user"));
        uploadMaterial(token);

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Explain orbital mechanics for satellites."
                    }
            """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("当前资料里没有检索到足够依据")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("General AI answer"))))
            .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    @Test
    void chatCanWorkAsGeneralAiQuestionWithoutUploadedMaterials() throws Exception {
        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        )).thenReturn(Optional.of(new LlmCompletion("Normal AI answer", "mock-model")));
        String token = registerAndLogin(uniqueName("rag-general-ai-user"));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Give me a short study plan.",
                      "mode": "GENERAL"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("当前资料里没有检索到足够依据"))))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("Normal AI answer")))
            .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    @Test
    void nonStreamingLongDocumentRequestStopsAfterFirstPartAndPromptsContinue() throws Exception {
        AtomicInteger partCounter = new AtomicInteger();
        when(thirdPartyLlmClient.effectiveModelName(any())).thenReturn("mock-model");
        when(thirdPartyLlmClient.answer(
            org.mockito.ArgumentMatchers.argThat(question ->
                question != null && question.contains("【超长文档分段生成指令】")
            ),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        )).thenAnswer(invocation -> {
            int part = partCounter.incrementAndGet();
            return Optional.of(new LlmCompletion("第 " + part + " 段内容", "mock-model"));
        });
        String token = registerAndLogin(uniqueName("rag-long-document-user"));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "请写一篇10000字的学习方法文档",
                      "mode": "GENERAL"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("第 1 段内容")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("输入“继续”生成第 2/4 部分")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("第 4 段内容"))))
            .andExpect(jsonPath("$.data.sources").isEmpty());

        org.assertj.core.api.Assertions.assertThat(partCounter.get()).isEqualTo(1);
    }

    @Test
    void nonStreamingLongDocumentContinueUsesPreviousConversation() throws Exception {
        AtomicInteger partCounter = new AtomicInteger();
        when(thirdPartyLlmClient.effectiveModelName(any())).thenReturn("mock-model");
        when(thirdPartyLlmClient.answer(
            org.mockito.ArgumentMatchers.argThat(question ->
                question != null && question.contains("【超长文档分段生成指令】")
            ),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        )).thenAnswer(invocation -> {
            int part = partCounter.incrementAndGet();
            return Optional.of(new LlmCompletion("第 " + part + " 段内容", "mock-model"));
        });
        String token = registerAndLogin(uniqueName("rag-long-document-continue-user"));

        var firstResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "请写一篇10000字的学习方法文档",
                      "mode": "GENERAL"
                    }
                    """))
            .andExpect(status().isOk())
            .andReturn();
        Long conversationId = extractLong(firstResult.getResponse().getContentAsString(), "conversationId");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "继续",
                      "mode": "GENERAL",
                      "conversationId": %d
                    }
                    """.formatted(conversationId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("第 2 段内容")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("输入“继续”生成第 3/4 部分")))
            .andExpect(jsonPath("$.data.sources").isEmpty());

        org.assertj.core.api.Assertions.assertThat(partCounter.get()).isEqualTo(2);
    }

    @Test
    void streamingDoneEventDoesNotRepeatVeryLongAnswer() throws Exception {
        String longAnswer = "长文内容".repeat(1001);
        when(thirdPartyLlmClient.effectiveModelName(any())).thenReturn("mock-model");
        when(thirdPartyLlmClient.answerStream(
            anyLong(),
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(true),
            org.mockito.ArgumentMatchers.eq("STUDY")
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onChunk = invocation.getArgument(4);
            onChunk.accept(longAnswer);
            return longAnswer;
        });
        String token = registerAndLogin(uniqueName("rag-stream-long-done-user"));

        var started = mockMvc.perform(post("/api/rag/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "请生成普通文本",
                      "mode": "GENERAL"
                    }
                    """))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
            .andReturn();

        started.getAsyncResult(30_000);
        var result = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body).contains("event: chunk");
        org.assertj.core.api.Assertions.assertThat(body).contains("event: done");
        org.assertj.core.api.Assertions.assertThat(body).contains("\"answerIncluded\":false");
        org.assertj.core.api.Assertions.assertThat(body).contains("\"answer\":\"\"");
    }

    @Test
    void streamingLongDocumentFirstPartPromptsManualContinue() throws Exception {
        when(thirdPartyLlmClient.effectiveModelName(any())).thenReturn("mock-model");
        when(thirdPartyLlmClient.answerStream(
            anyLong(),
            org.mockito.ArgumentMatchers.argThat(question ->
                question != null && question.contains("【超长文档分段生成指令】")
            ),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(true),
            org.mockito.ArgumentMatchers.eq("STUDY")
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<String> onChunk = invocation.getArgument(4);
            onChunk.accept("第 1 段流式内容");
            return "第 1 段流式内容";
        });
        String token = registerAndLogin(uniqueName("rag-stream-long-document-continue-user"));

        var started = mockMvc.perform(post("/api/rag/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "请写一篇10000字的学习方法文档",
                      "mode": "GENERAL"
                    }
                    """))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
            .andReturn();

        started.getAsyncResult(30_000);
        var result = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body).contains("event: chunk");
        org.assertj.core.api.Assertions.assertThat(body).contains("第 1 段流式内容");
        org.assertj.core.api.Assertions.assertThat(body).contains("输入“继续”生成第 2/4 部分");
        org.assertj.core.api.Assertions.assertThat(body).contains("event: done");
    }

    @Test
    void generalChatRestoresTemporaryMaterialFromConversationWhenNextTurnOmitsAttachment() throws Exception {
        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts ->
                excerpts != null && excerpts.stream().anyMatch(excerpt -> excerpt.contains("TEMP_CONTEXT_MARKER"))
            ),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        )).thenReturn(Optional.of(new LlmCompletion("临时资料里提到了 TEMP_CONTEXT_MARKER。", "mock-model")));
        String token = registerAndLogin(uniqueName("rag-temporary-context-user"));

        var firstResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "这份临时资料里有什么标记？",
                      "mode": "GENERAL",
                      "temporaryMaterial": {
                        "id": "temp-context-1",
                        "title": "临时资料.txt",
                        "originalName": "临时资料.txt",
                        "sourceType": "TXT",
                        "text": "TEMP_CONTEXT_MARKER 是这份临时资料里的关键标记，用来验证后续轮次仍能读取上下文。",
                        "excerpt": "TEMP_CONTEXT_MARKER 是关键标记",
                        "fileSize": 120
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("TEMP_CONTEXT_MARKER")))
            .andReturn();
        Long conversationId = extractLong(firstResult.getResponse().getContentAsString(), "conversationId");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "刚才那份资料里的标记是什么？",
                      "mode": "GENERAL",
                      "conversationId": %d
                    }
                    """.formatted(conversationId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("TEMP_CONTEXT_MARKER")))
            .andExpect(jsonPath("$.data.conversationId").value(conversationId.intValue()));

        verify(thirdPartyLlmClient, times(2)).answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts ->
                excerpts != null && excerpts.stream().anyMatch(excerpt -> excerpt.contains("TEMP_CONTEXT_MARKER"))
            ),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        );
    }

    @Test
    void generalChatUsesStoredTemporaryMaterialContextWhenRequestOnlySendsReference() throws Exception {
        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts ->
                excerpts != null && excerpts.stream().anyMatch(excerpt -> excerpt.contains("STORED_CONTEXT_MARKER_AFTER_PREVIEW"))
            ),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        )).thenReturn(Optional.of(new LlmCompletion("已读取后端临时资料全文：STORED_CONTEXT_MARKER_AFTER_PREVIEW。", "mock-model")));

        String username = uniqueName("rag-stored-temporary-context-user");
        String token = registerAndLogin(username);
        Long userId = userRepository.findByUsername(username).orElseThrow().getId();
        String contextId = "stored-temp-" + UUID.randomUUID();
        String fullText = "普通预览内容。".repeat(5_000)
            + "\nSTORED_CONTEXT_MARKER_AFTER_PREVIEW 表示后端按临时资料 ID 恢复了超过前端预览范围的全文。";
        TemporaryMaterialContextEntity context = new TemporaryMaterialContextEntity();
        context.setId(contextId);
        context.setOwnerId(userId);
        context.setTitle("长临时资料.txt");
        context.setOriginalName("长临时资料.txt");
        context.setSourceType("TXT");
        context.setText(fullText);
        context.setExcerpt("普通预览内容。");
        context.setFileSize((long) fullText.getBytes(StandardCharsets.UTF_8).length);
        temporaryMaterialContextRepository.save(context);

        var firstResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "STORED_CONTEXT_MARKER_AFTER_PREVIEW 是什么？",
                      "mode": "GENERAL",
                      "temporaryMaterial": {
                        "id": "%s",
                        "title": "长临时资料.txt",
                        "originalName": "长临时资料.txt",
                        "sourceType": "TXT",
                        "text": "这里只是一小段前端预览，不包含真正的目标标记。",
                        "excerpt": "普通预览内容",
                        "fileSize": 120,
                        "contextStored": true
                      }
                    }
                    """.formatted(contextId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("STORED_CONTEXT_MARKER_AFTER_PREVIEW")))
            .andReturn();
        Long conversationId = extractLong(firstResult.getResponse().getContentAsString(), "conversationId");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "上一轮临时资料里的 STORED_CONTEXT_MARKER_AFTER_PREVIEW 是什么？",
                      "mode": "GENERAL",
                      "conversationId": %d
                    }
                    """.formatted(conversationId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("STORED_CONTEXT_MARKER_AFTER_PREVIEW")));

        verify(thirdPartyLlmClient, times(2)).answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts ->
                excerpts != null && excerpts.stream().anyMatch(excerpt -> excerpt.contains("STORED_CONTEXT_MARKER_AFTER_PREVIEW"))
            ),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        );
    }

    @Test
    void streamingChatReturnsErrorEventWhenLlmProviderFails() throws Exception {
        when(thirdPartyLlmClient.answerStream(
            anyLong(),
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq(true),
            org.mockito.ArgumentMatchers.eq("STUDY")
        )).thenThrow(new LlmProviderException("AI 模型响应失败或超时，请缩短问题、减少资料内容，或改用“继续”分段生成后重试。"));
        String token = registerAndLogin(uniqueName("rag-stream-error-user"));

        var started = mockMvc.perform(post("/api/rag/chat/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "请生成一段很长的回答",
                      "mode": "GENERAL"
                    }
                    """))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
            .andReturn();

        started.getAsyncResult(30_000);
        var result = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body).contains("event: error");
        org.assertj.core.api.Assertions.assertThat(body).contains("AI 模型响应失败或超时");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("event: done");
    }

    @Test
    void nonStreamingChatIncludesHistoryInLlmQuestion() throws Exception {
        when(thirdPartyLlmClient.answer(
            org.mockito.ArgumentMatchers.argThat(question ->
                question != null
                    && question.contains("\u6700\u8fd1\u5bf9\u8bdd\u539f\u6587")
                    && question.contains("\u6211\u53eb\u5c0f\u660e")
                    && question.contains("\u5f53\u524d\u95ee\u9898\uff1a\u6211\u53eb\u4ec0\u4e48")
            ),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        )).thenReturn(Optional.of(new LlmCompletion("\u4f60\u53eb\u5c0f\u660e\u3002", "mock-model")));
        String token = registerAndLogin(uniqueName("rag-general-history-user"));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "\u6211\u53eb\u4ec0\u4e48\uff1f",
                      "mode": "GENERAL",
                      "history": [
                        { "role": "user", "content": "\u6211\u53eb\u5c0f\u660e" },
                        { "role": "assistant", "content": "\u597d\u7684\uff0c\u6211\u8bb0\u4f4f\u4e86\u3002" }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("\u5c0f\u660e")))
            .andExpect(jsonPath("$.data.sources").isEmpty());

        verify(thirdPartyLlmClient).answer(
            org.mockito.ArgumentMatchers.argThat(question ->
                question != null
                    && question.contains("\u6700\u8fd1\u5bf9\u8bdd\u539f\u6587")
                    && question.contains("\u6211\u53eb\u5c0f\u660e")
                    && question.contains("\u5f53\u524d\u95ee\u9898\uff1a\u6211\u53eb\u4ec0\u4e48")
            ),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        );
    }

    @Test
    void nonStreamingChatUsesLongTermMemoryAfterRecentWindow() throws Exception {
        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        )).thenReturn(Optional.of(new LlmCompletion("Memory answer", "mock-model")));
        String token = registerAndLogin(uniqueName("rag-long-memory-user"));

        var firstResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "我的项目代号是 memory-anchor-alpha",
                      "mode": "GENERAL"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        Long conversationId = extractLong(firstResult.getResponse().getContentAsString(), "conversationId");

        for (int i = 1; i <= 8; i++) {
            mockMvc.perform(post("/api/rag/chat")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "question": "第 %d 轮普通追问",
                          "mode": "GENERAL",
                          "conversationId": %d
                        }
                        """.formatted(i, conversationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        }

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "请根据之前信息回答项目代号",
                      "mode": "GENERAL",
                      "conversationId": %d
                    }
                    """.formatted(conversationId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("Memory answer")));

        verify(thirdPartyLlmClient).answer(
            org.mockito.ArgumentMatchers.argThat(question ->
                question != null
                    && question.contains("长期会话摘要")
                    && question.contains("memory-anchor-alpha")
                    && question.contains("当前问题：请根据之前信息回答项目代号")
            ),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        );
    }

    @Test
    void generalModeChatDoesNotSearchUploadedMaterials() throws Exception {
        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        )).thenReturn(Optional.of(new LlmCompletion("Normal AI answer without book context", "mock-model")));
        String token = registerAndLogin(uniqueName("rag-general-mode-user"));
        uploadMaterial(token);

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "How does RAG retrieve relevant course chunks?",
                      "mode": "GENERAL"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("Normal AI answer without book context")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("资料中未检索到相关页码"))))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Course RAG Material"))))
            .andExpect(jsonPath("$.data.sources").isEmpty());

        verify(thirdPartyLlmClient).answer(
            org.mockito.ArgumentMatchers.eq("How does RAG retrieve relevant course chunks?"),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(true)
        );
    }

    @Test
    void readerAskPrioritizesCurrentChunkAndRequestsOriginalQuotes() throws Exception {
        String token = registerAndLogin(uniqueName("rag-reader-current-chunk-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Reader Mode Material",
            "当前页核心原文：事务隔离用于控制并发读写的一致性。相关补充：索引可以帮助数据库提高查询速度。"
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Long chunkId = extractLong(chunksResult.getResponse().getContentAsString(), "id");

        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts ->
                excerpts != null
                    && !excerpts.isEmpty()
                    && excerpts.get(0).contains("[当前阅读位置，优先依据]")
                    && excerpts.get(0).contains("当前页核心原文")
                    && excerpts.get(0).contains("原文：")
            ),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(false)
        )).thenReturn(Optional.of(new LlmCompletion("结论：事务隔离用于并发一致性。\n原文：当前页核心原文：事务隔离用于控制并发读写的一致性。", "mock-model")));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "这页在讲什么？",
                      "mode": "MATERIAL",
                      "materialId": %d,
                      "chunkId": %d
                    }
                    """.formatted(materialId, chunkId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("原文：")))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(chunkId.intValue()));
    }

    @Test
    void materialModeRequiresMaterialId() throws Exception {
        String token = registerAndLogin(uniqueName("rag-material-missing-user"));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Only search the current material.",
                      "mode": "MATERIAL"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("materialId is required for current material chat"));
    }

    @Test
    void readerLocalQuestionUsesOnlyCurrentContextInsteadOfGlobalMatches() throws Exception {
        String token = registerAndLogin(uniqueName("rag-reader-local-context-user"));
        String databaseParagraph = "Database indexes speed up query lookup. ".repeat(20);
        String currentParagraph = "Photosynthesis converts sunlight into stored plant energy.";
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Mixed Topic Reader Material",
            databaseParagraph + "\n\n" + currentParagraph
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        String chunksBody = chunksResult.getResponse().getContentAsString();
        Long currentChunkId = extractChunkIdContaining(chunksBody, "Photosynthesis");

        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts ->
                excerpts != null
                    && excerpts.size() == 1
                    && excerpts.get(0).contains("Photosynthesis")
            ),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(Optional.of(new LlmCompletion("This page explains photosynthesis.", "mock-model")));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "What is this page about? Mention database if relevant.",
                      "mode": "MATERIAL",
                      "answerStyle": "HOMEWORK",
                      "materialId": %d,
                      "chunkId": %d
                    }
                    """.formatted(materialId, currentChunkId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("photosynthesis")))
            .andExpect(jsonPath("$.data.sources.length()").value(1))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(currentChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("Photosynthesis")));
    }

    @Test
    void definitionQuestionLocatesChunkContainingRequestedTerm() throws Exception {
        String token = registerAndLogin(uniqueName("rag-definition-user"));
        String distractorParagraph = "Indexes improve lookup performance for database queries. ".repeat(16);
        String definitionParagraph = "RAG refers to retrieval augmented generation. It combines retrieved material with model generation.";
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Definition Material",
            distractorParagraph + "\n\n" + definitionParagraph
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Long definitionChunkId = extractChunkIdContaining(chunksResult.getResponse().getContentAsString(), "RAG refers");

        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts ->
                excerpts != null
                    && !excerpts.isEmpty()
                    && excerpts.get(0).contains("RAG refers")
            ),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(false)
        )).thenReturn(Optional.of(new LlmCompletion("RAG refers to retrieval augmented generation.", "mock-model")));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "What is RAG?",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(definitionChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("RAG refers")));
    }

    @Test
    void functionQuestionLocatesChunkExplainingTermUsage() throws Exception {
        String token = registerAndLogin(uniqueName("rag-function-user"));
        String distractorParagraph = "RAG refers to retrieval augmented generation. ".repeat(20);
        String functionParagraph = "Indexes are used for speeding up lookup and filtering in database queries.";
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Function Material",
            distractorParagraph + "\n\n" + functionParagraph
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Long functionChunkId = extractChunkIdContaining(chunksResult.getResponse().getContentAsString(), "Indexes are used for");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "What are indexes used for?",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(functionChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("Indexes are used for")));
    }

    @Test
    void queryExpansionCanRetrieveChunkWhenOriginalQuestionHasNoMatchingTerms() throws Exception {
        String token = registerAndLogin(uniqueName("rag-query-expansion-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Expansion Material",
            "alpha retrieval marker appears only here."
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Long expandedChunkId = extractChunkIdContaining(chunksResult.getResponse().getContentAsString(), "alpha retrieval marker");

        when(thirdPartyLlmClient.expandQuery(anyString()))
            .thenReturn(Optional.of(List.of("alpha retrieval marker")));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Can you recover the clue?",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(expandedChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("alpha retrieval marker")));

        verify(thirdPartyLlmClient).expandQuery("Can you recover the clue?");
    }

    @Test
    void retrievalUsesPersistedChunkKeywords() throws Exception {
        String token = registerAndLogin(uniqueName("rag-chunk-keywords-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Keyword Metadata Material",
            "alpha marker body text."
        );
        Long chunkId = extractChunkIdContaining(
            mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "alpha marker"
        );
        materialChunkRepository.findById(chunkId).ifPresent(chunk -> {
            chunk.setKeywords("rarekeyword");
            materialChunkRepository.save(chunk);
        });

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Where is rarekeyword mentioned?",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(chunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("alpha marker")));
    }

    @Test
    void identicalQuestionReusesRetrievalResultCache() throws Exception {
        String token = registerAndLogin(uniqueName("rag-retrieval-cache-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Retrieval Cache Material",
            "cache marker retrieval text appears in this material."
        );
        when(thirdPartyLlmClient.expandQuery(anyString()))
            .thenReturn(Optional.of(List.of("cache marker retrieval text")));

        String requestBody = """
            {
              "question": "Where is the cache marker?",
              "mode": "MATERIAL",
              "materialId": %d
            }
            """.formatted(materialId);

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("cache marker")));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("cache marker")));

        verify(thirdPartyLlmClient, times(1)).expandQuery("Where is the cache marker?");
    }

    @Test
    void comparisonQuestionPrefersChunkContainingBothTerms() throws Exception {
        String token = registerAndLogin(uniqueName("rag-comparison-user"));
        String singleTermParagraph = "BM25 is a keyword matching retrieval method. ".repeat(20);
        String comparisonParagraph = "BM25 and vector search are different: BM25 matches exact terms, while vector search captures semantic similarity.";
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Comparison Material",
            singleTermParagraph + "\n\n" + comparisonParagraph
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Long comparisonChunkId = extractChunkIdContaining(chunksResult.getResponse().getContentAsString(), "vector search are different");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Difference between BM25 and vector search?",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(comparisonChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("vector search are different")));
    }

    @Test
    void chineseDefinitionQuestionLocatesRequestedTerm() throws Exception {
        String token = registerAndLogin(uniqueName("rag-cn-definition-user"));
        String distractorParagraph = "\u7d22\u5f15\u53ef\u4ee5\u63d0\u9ad8\u67e5\u8be2\u6548\u7387\u3002".repeat(40);
        String definitionParagraph = "RAG\u662f\u6307\u68c0\u7d22\u589e\u5f3a\u751f\u6210\uff0c\u901a\u8fc7\u68c0\u7d22\u8d44\u6599\u7247\u6bb5\u6765\u8f85\u52a9\u6a21\u578b\u56de\u7b54\u3002";
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Chinese Definition Material",
            distractorParagraph + "\n\n" + definitionParagraph
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Long definitionChunkId = extractChunkIdContaining(chunksResult.getResponse().getContentAsString(), "RAG");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "\u4ec0\u4e48\u662f RAG\uff1f",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(definitionChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("RAG")));
    }

    @Test
    void chineseFunctionQuestionLocatesUsageChunk() throws Exception {
        String token = registerAndLogin(uniqueName("rag-cn-function-user"));
        String distractorParagraph = "RAG\u662f\u6307\u68c0\u7d22\u589e\u5f3a\u751f\u6210\u3002".repeat(40);
        String functionParagraph = "\u7d22\u5f15\u7684\u4f5c\u7528\u662f\u52a0\u5feb\u6570\u636e\u5e93\u67e5\u627e\u548c\u8fc7\u6ee4\u901f\u5ea6\u3002";
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Chinese Function Material",
            distractorParagraph + "\n\n" + functionParagraph
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Long functionChunkId = extractChunkIdContaining(chunksResult.getResponse().getContentAsString(), "\u7d22\u5f15\u7684\u4f5c\u7528");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "\u7d22\u5f15\u6709\u4ec0\u4e48\u7528\uff1f",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(functionChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("\u7d22\u5f15\u7684\u4f5c\u7528")));
    }

    @Test
    void chineseComparisonQuestionLocatesChunkContainingBothTerms() throws Exception {
        String token = registerAndLogin(uniqueName("rag-cn-comparison-user"));
        String singleTermParagraph = "BM25\u662f\u4e00\u79cd\u5173\u952e\u8bcd\u5339\u914d\u68c0\u7d22\u65b9\u6cd5\u3002".repeat(40);
        String comparisonParagraph = "BM25\u548c\u5411\u91cf\u68c0\u7d22\u7684\u533a\u522b\u5728\u4e8e\uff1aBM25\u5173\u6ce8\u8bcd\u9879\u5339\u914d\uff0c\u5411\u91cf\u68c0\u7d22\u5173\u6ce8\u8bed\u4e49\u76f8\u4f3c\u5ea6\u3002";
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Chinese Comparison Material",
            singleTermParagraph + "\n\n" + comparisonParagraph
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Long comparisonChunkId = extractChunkIdContaining(chunksResult.getResponse().getContentAsString(), "\u5411\u91cf\u68c0\u7d22\u7684\u533a\u522b");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "BM25\u548c\u5411\u91cf\u68c0\u7d22\u6709\u4ec0\u4e48\u533a\u522b\uff1f",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(comparisonChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("\u5411\u91cf\u68c0\u7d22\u7684\u533a\u522b")));
    }

    @Test
    void followUpQuestionUsesHistoryTopicForRetrieval() throws Exception {
        String token = registerAndLogin(uniqueName("rag-follow-up-user"));
        String distractorParagraph = "UDP\u534f\u8bae\u7684\u4f18\u70b9\u662f\u5ef6\u8fdf\u4f4e\uff0c\u7f3a\u70b9\u662f\u4e0d\u4fdd\u8bc1\u53ef\u9760\u4f20\u8f93\u3002".repeat(40);
        String tcpParagraph = "TCP\u534f\u8bae\u7684\u4f18\u70b9\u662f\u53ef\u9760\u4f20\u8f93\u3001\u6709\u5e8f\u5230\u8fbe\uff0c\u7f3a\u70b9\u662f\u63e1\u624b\u548c\u91cd\u4f20\u5e26\u6765\u989d\u5916\u5f00\u9500\u3002";
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Follow-up Retrieval Material",
            distractorParagraph + "\n\n" + tcpParagraph
        );
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
        Long tcpChunkId = extractChunkIdContaining(chunksResult.getResponse().getContentAsString(), "TCP");

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "\u90a3\u5b83\u7684\u4f18\u7f3a\u70b9\u5462\uff1f",
                      "mode": "MATERIAL",
                      "materialId": %d,
                      "history": [
                        {"role": "user", "content": "\u4ec0\u4e48\u662f TCP\u534f\u8bae\uff1f"},
                        {"role": "assistant", "content": "TCP\u534f\u8bae\u662f\u9762\u5411\u8fde\u63a5\u7684\u4f20\u8f93\u5c42\u534f\u8bae\u3002"}
                      ]
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(tcpChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("TCP")));
    }


    @Test
    void materialModeDoesNotUseChunksFromOtherMaterialsEvenWhenOtherMaterialMatchesBetter() throws Exception {
        String token = registerAndLogin(uniqueName("rag-material-isolation-user"));
        Long selectedMaterialId = uploadMaterialAndReturnId(
            token,
            "Plant Biology Material",
            "Chlorophyll converts sunlight into stored plant energy."
        );
        Long otherMaterialId = uploadMaterialAndReturnId(
            token,
            "Database Index Material",
            "Database indexes speed up database lookup and query filtering."
        );

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "How do database indexes speed lookup and query filtering?",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(selectedMaterialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("Database Index Material")
            )))
            .andExpect(jsonPath("$.data.sources").isEmpty());

        verify(thirdPartyLlmClient).answer(
            org.mockito.ArgumentMatchers.eq("How do database indexes speed lookup and query filtering?"),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(false)
        );
    }

    @Test
    void chatDoesNotAddNoSourceNoticeForCasualGreeting() throws Exception {
        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(false)
        )).thenReturn(Optional.of(new LlmCompletion("资料中未检索到相关页码。你好！有什么可以帮你的？", "mock-model")));
        String token = registerAndLogin(uniqueName("rag-greeting-user"));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "你好"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("你好")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("资料中未检索到"))))
            .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    @Test
    void chatReturnsNaturalFallbackWhenCasualGreetingOnlyGetsNoSourceNotice() throws Exception {
        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts -> excerpts != null && excerpts.isEmpty()),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(false)
        )).thenReturn(Optional.of(new LlmCompletion("资料中未检索到相关页码。", "mock-model")));
        String token = registerAndLogin(uniqueName("rag-empty-greeting-user"));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "你好"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value("你好！有什么可以帮你？"))
            .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    @Test
    void chatCanLimitRetrievalToSelectedMaterial() throws Exception {
        String token = registerAndLogin(uniqueName("rag-scope-user"));
        Long ragMaterialId = uploadMaterialAndReturnId(token);
        uploadMaterialAndReturnId(
            token,
            "Database Index Material",
            "Indexes speed up database lookup and query filtering."
        );

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "How does RAG retrieve relevant course chunks?",
                      "materialId": %d
                    }
                    """.formatted(ragMaterialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].materialId").value(ragMaterialId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].chunkId").isNumber())
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("Course RAG Material")));
    }

    @Test
    void hydeAnswerEmbeddingCanRetrieveChunkWhenOriginalQuestionHasNoTerms() throws Exception {
        String token = registerAndLogin(uniqueName("rag-hyde-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "HyDE Vector Material",
            "semantic anchor vector target passage"
        );
        Long targetChunkId = extractChunkIdContaining(
            mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(),
            "semantic anchor vector target"
        );

        materialChunkRepository.findById(targetChunkId).ifPresent(chunk -> {
            chunk.setEmbeddingJson("[1.0,0.0]");
            materialChunkRepository.save(chunk);
        });
        when(thirdPartyLlmClient.generateHydeAnswer("Can you infer the hidden concept?"))
            .thenReturn(Optional.of("semantic anchor vector target passage"));
        when(embeddingClient.embedQuery("Can you infer the hidden concept?"))
            .thenReturn(Optional.of(List.of(0.0, 1.0)));
        when(embeddingClient.embedQuery("semantic anchor vector target passage"))
            .thenReturn(Optional.of(List.of(1.0, 0.0)));

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "Can you infer the hidden concept?",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sources[0].chunkId").value(targetChunkId.intValue()))
            .andExpect(jsonPath("$.data.sources[0].excerpt").value(org.hamcrest.Matchers.containsString("semantic anchor vector target")));

        verify(thirdPartyLlmClient).generateHydeAnswer("Can you infer the hidden concept?");
        verify(embeddingClient, atLeastOnce()).embedQuery("semantic anchor vector target passage");
    }

    @Test
    void selectedTextQuestionUsesSelectedExcerptEvenWhenNoChunkSourcesMatch() throws Exception {
        when(thirdPartyLlmClient.answer(
            anyString(),
            org.mockito.ArgumentMatchers.argThat(excerpts ->
                excerpts != null
                    && excerpts.size() == 1
                    && excerpts.get(0).contains("[用户选中内容]")
                    && excerpts.get(0).contains("事务隔离用于控制并发读写的一致性")
            ),
            org.mockito.ArgumentMatchers.anyList(),
            org.mockito.ArgumentMatchers.eq(false),
            org.mockito.ArgumentMatchers.eq("HOMEWORK")
        )).thenReturn(Optional.of(new LlmCompletion("事务隔离用于控制并发读写的一致性，核心目的是保证数据在并发场景下的正确性。", "mock-model")));

        String token = registerAndLogin(uniqueName("rag-selected-text-user"));
        Long materialId = uploadMaterialAndReturnId(
            token,
            "Selected Text Material",
            "事务隔离用于控制并发读写的一致性。索引用于提升查询速度。"
        );

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "请解释这段内容",
                      "mode": "MATERIAL",
                      "materialId": %d,
                      "selectedText": "事务隔离用于控制并发读写的一致性",
                      "answerStyle": "HOMEWORK"
                    }
                    """.formatted(materialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("事务隔离用于控制并发读写的一致性")))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("未检索到足够页码依据"))))
            .andExpect(jsonPath("$.data.sources").isEmpty());
    }

    @Test
    void materialModeNeverUsesOtherMaterials() throws Exception {
        String token = registerAndLogin(uniqueName("rag-material-isolated-user"));
        Long currentMaterialId = uploadMaterialAndReturnId(
            token,
            "Current Biology Material",
            "Photosynthesis converts sunlight into stored chemical energy."
        );
        Long otherMaterialId = uploadMaterialAndReturnId(
            token,
            "Other RAG Material",
            "RAG retrieves relevant course chunks before answering student questions. RAG retrieval RAG chunks."
        );

        mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "How does RAG retrieve relevant course chunks?",
                      "mode": "MATERIAL",
                      "materialId": %d
                    }
                    """.formatted(currentMaterialId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Other RAG Material"))))
            .andExpect(jsonPath("$.data.sources[*].materialId").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(otherMaterialId.intValue()))));
    }

    private void uploadMaterial(String token) throws Exception {
        uploadMaterialAndReturnId(token);
    }

    private Long uploadMaterialAndReturnId(String token) throws Exception {
        return uploadMaterialAndReturnId(
            token,
            "Course RAG Material",
            "RAG retrieves relevant course chunks before answering student questions."
        );
    }

    private Long uploadMaterialAndReturnId(String token, String title, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "rag.txt",
            MediaType.TEXT_PLAIN_VALUE,
            content.getBytes(StandardCharsets.UTF_8)
        );

        var result = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", title)
                .param("sourceType", "TXT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        return extractLong(result.getResponse().getContentAsString(), "id");
    }

    private Long createChatAndReturnQuestionId(String token, String question) throws Exception {
        var chatResult = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "question": "%s"
                    }
                    """.formatted(question)))
            .andExpect(status().isOk())
            .andReturn();

        return extractLong(chatResult.getResponse().getContentAsString(), "questionId");
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "RAG User"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        var loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();

        return extractString(loginResult.getResponse().getContentAsString(), "token");
    }

    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String extractString(String body, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(fieldName + " not found in response: " + body);
        }
        return matcher.group(1);
    }

    private Long extractLong(String body, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(fieldName + " not found in response: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }

    private Long extractChunkIdContaining(String body, String marker) {
        Matcher matcher = Pattern
            .compile("\\{[^{}]*\"id\"\\s*:\\s*(\\d+)[^{}]*\"chunkText\"\\s*:\\s*\"[^\"]*" + Pattern.quote(marker) + "[^\"]*\"[^{}]*\\}")
            .matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("chunk containing marker not found in response: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }
}
