package com.mytext.learningassistant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.embedding.EmbeddingClient;
import com.mytext.learningassistant.llm.ThirdPartyLlmClient;
import com.mytext.learningassistant.material.MaterialProcessingJobService;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.material-processing.scheduler-enabled=false")
@AutoConfigureMockMvc
class RagStructuredMaterialQaTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MaterialProcessingJobService materialProcessingJobService;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @MockitoBean
    private ThirdPartyLlmClient thirdPartyLlmClient;

    @BeforeEach
    void useLocalFallbackByDefault() {
        when(embeddingClient.embedQuery(anyString())).thenReturn(Optional.empty());
        when(embeddingClient.embedDocument(anyString())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean(), any())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.summarize(anyString(), anyList())).thenReturn(Optional.empty());
    }

    @Test
    void directoryQuestionUsesWholeMaterialEvenWhenReaderIsOnLaterChunk() throws Exception {
        String token = registerAndLogin("rag-directory-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Structured Directory Material",
            """
            目录
            第一章 数据库基础
            第二章 SQL 查询
            第三章 索引优化

            第一章 数据库基础
            关系模型、表、行、列是数据库基础内容。

            第二章 SQL 查询
            SELECT、WHERE、JOIN 是 SQL 查询的核心内容。

            第三章 索引优化
            BTree index marker later-page-noise 用于提升查询过滤速度。
            """);
        Long laterChunkId = findChunkIdContaining(getChunks(token, materialId), "later-page-noise");

        JsonNode answer = chat(token, "章节目录有哪些内容？", materialId, laterChunkId);

        assertAnswerContains(answer, "第一章 数据库基础");
        assertAnswerContains(answer, "第二章 SQL 查询");
        assertAnswerContains(answer, "第三章 索引优化");
        assertSourceContains(answer, "目录");
    }

    @Test
    void chapterQuestionUsesRequestedChapterInsteadOfCurrentReaderChunk() throws Exception {
        String token = registerAndLogin("rag-chapter-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Structured Chapter Material",
            """
            第一章 数据库基础
            关系模型、表、行、列是数据库基础内容。

            第二章 SQL 查询
            SECOND_CHAPTER_MARKER SELECT、WHERE、JOIN 是 SQL 查询的核心内容。
            聚合、排序、分页也属于第二章的查询主题。

            第三章 索引优化
            THIRD_CHAPTER_MARKER BTree index later-page-noise 用于提升查询过滤速度。
            """);
        Long thirdChapterChunkId = findChunkIdContaining(getChunks(token, materialId), "THIRD_CHAPTER_MARKER");

        JsonNode answer = chat(token, "第二章主要讲什么？", materialId, thirdChapterChunkId);

        assertAnswerContains(answer, "SECOND_CHAPTER_MARKER");
        assertAnswerNotContains(answer, "THIRD_CHAPTER_MARKER");
        assertSourceContains(answer, "SECOND_CHAPTER_MARKER");
    }

    @Test
    void bodyQuestionKeepsRealBodyEvidenceAheadOfContentsLocator() throws Exception {
        String token = registerAndLogin("rag-body-source-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Body Source Material",
            """
            Contents
            Chapter 1 Overview
            Chapter 2 SQL Query
            Chapter 3 Index Optimization

            Chapter 1 Overview
            INTRO_MARKER This part only introduces database basics.

            Chapter 2 SQL Query
            BODY_SQL_JOIN_MARKER JOIN connects rows from multiple tables by matching keys and conditions.
            BODY_SQL_WHERE_MARKER WHERE filters rows before the final result is returned.

            Chapter 3 Index Optimization
            INDEX_MARKER BTree indexes improve lookup speed.
            """
        );
        Long indexChunkId = findChunkIdContaining(getChunks(token, materialId), "INDEX_MARKER");

        JsonNode answer = chat(token, "What does Chapter 2 say about JOIN?", materialId, indexChunkId);

        assertAnswerContains(answer, "BODY_SQL_JOIN_MARKER");
        assertSourceContains(answer, "BODY_SQL_JOIN_MARKER");
        Assertions.assertFalse(answer.at("/sources/0/excerpt").asText().contains("Contents"), answer.toPrettyString());
    }

    @Test
    void sourceScoreReturnedToUiIsNormalized() throws Exception {
        String token = registerAndLogin("rag-score-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Score Material",
            "RAG refers to retrieval augmented generation. It combines retrieved material with model generation."
        );

        JsonNode answer = chat(token, "什么是 RAG？", materialId, null);

        double score = answer.at("/sources/0/score").asDouble();
        Assertions.assertTrue(score >= 0.0 && score <= 1.0, answer.toPrettyString());
    }

    @Test
    void localPageQuestionStillUsesCurrentReaderChunk() throws Exception {
        String token = registerAndLogin("rag-current-page-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Current Page Material",
            """
            第一章 基础
            CURRENT_PAGE_MARKER 当前页正在解释表结构。

            第二章 SQL 查询
            GLOBAL_KEYWORD_MARKER 这一章讲 SELECT 和 JOIN。
            """);
        Long currentChunkId = findChunkIdContaining(getChunks(token, materialId), "CURRENT_PAGE_MARKER");

        JsonNode answer = chat(token, "这页主要讲什么？", materialId, currentChunkId);

        assertAnswerContains(answer, "CURRENT_PAGE_MARKER");
        assertAnswerNotContains(answer, "GLOBAL_KEYWORD_MARKER");
        assertSourceContains(answer, "CURRENT_PAGE_MARKER");
    }

    @Test
    void keywordQuestionSearchesWholeMaterialInsteadOfOnlyCurrentReaderChunk() throws Exception {
        String token = registerAndLogin("rag-keyword-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Keyword Material",
            """
            第一章 基础
            CURRENT_PAGE_ONLY_MARKER 当前页只介绍数据库表。

            第二章 SQL 查询
            JOIN_KEYWORD_MARKER JOIN 用于把多个表按关联条件连接起来。
            """);
        Long currentChunkId = findChunkIdContaining(getChunks(token, materialId), "CURRENT_PAGE_ONLY_MARKER");

        JsonNode answer = chat(token, "JOIN 是什么？", materialId, currentChunkId);

        assertAnswerContains(answer, "JOIN_KEYWORD_MARKER");
        assertSourceContains(answer, "JOIN_KEYWORD_MARKER");
    }

    private JsonNode chat(String token, String question, Long materialId, Long currentChunkId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("mode", "MATERIAL");
        payload.put("materialId", materialId);
        if (currentChunkId != null) {
            payload.put("chunkId", currentChunkId);
            payload.put("currentPageChunkIds", List.of(currentChunkId));
        }
        var result = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private Long uploadTextMaterial(String token, String title, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "structured.txt",
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
        Long materialId = extractLong(result.getResponse().getContentAsString(), "id");
        drainMaterialJobs();
        return materialId;
    }

    private JsonNode getChunks(String token, Long materialId) throws Exception {
        var result = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    private Long findChunkIdContaining(JsonNode chunks, String marker) {
        for (JsonNode chunk : chunks) {
            if (chunk.path("chunkText").asText().contains(marker)) {
                return chunk.path("id").asLong();
            }
        }
        throw new IllegalStateException("chunk containing marker not found: " + chunks.toPrettyString());
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "displayName": "RAG Structured User"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk());

        var result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/token").asText();
    }

    private void drainMaterialJobs() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            int executed = materialProcessingJobService.runReadyJobs(20);
            if (executed == 0) {
                return;
            }
            Thread.sleep(20);
        }
        Assertions.fail("material processing jobs did not drain before timeout");
    }

    private Long extractLong(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("field not found: " + field + " in " + json);
        }
        return Long.parseLong(matcher.group(1));
    }

    private void assertAnswerContains(JsonNode data, String expected) {
        Assertions.assertTrue(data.path("answer").asText().contains(expected), data.toPrettyString());
    }

    private void assertAnswerNotContains(JsonNode data, String forbidden) {
        Assertions.assertFalse(data.path("answer").asText().contains(forbidden), data.toPrettyString());
    }

    private void assertSourceContains(JsonNode data, String expected) {
        Assertions.assertTrue(data.at("/sources/0/excerpt").asText().contains(expected), data.toPrettyString());
    }
}
