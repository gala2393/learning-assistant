package com.mytext.learningassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mytext.learningassistant.llm.LlmCompletion;
import com.mytext.learningassistant.llm.ThirdPartyLlmClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class RagApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ThirdPartyLlmClient thirdPartyLlmClient;

    @BeforeEach
    void useLocalFallbackByDefault() {
        when(thirdPartyLlmClient.answer(anyString(), anyList())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean(), any())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.summarize(anyString(), anyList())).thenReturn(Optional.empty());
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
            .andExpect(jsonPath("$.data[0].questionId").value(questionId.intValue()));
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
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("当前资料未检索到足够页码")))
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
            .andExpect(jsonPath("$.data.summaryType").value("AUTO"))
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
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("*"))))
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
        when(thirdPartyLlmClient.summarize(anyString(), anyList()))
            .thenReturn(Optional.of(new LlmCompletion("Provider summary", "mock-model")));
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
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("当前资料未检索到足够页码")))
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
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("当前资料未检索到足够页码"))))
            .andExpect(jsonPath("$.data.answer").value(org.hamcrest.Matchers.containsString("Normal AI answer")))
            .andExpect(jsonPath("$.data.sources").isEmpty());
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
