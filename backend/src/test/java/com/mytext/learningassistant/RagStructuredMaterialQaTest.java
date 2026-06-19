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
import com.mytext.learningassistant.llm.LlmCompletion;
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
        assertSelectedSourceHasRetrievalRoute(answer);
        Assertions.assertFalse(answer.at("/sources/0/excerpt").asText().contains("Contents"), answer.toPrettyString());
    }

    @Test
    void generatedAnswerGroundsSourcesToActuallyUsedBodyChunk() throws Exception {
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean()))
            .thenReturn(Optional.of(new LlmCompletion(
                "Chapter 2 explains that BODY_SQL_JOIN_MARKER JOIN connects rows from multiple tables.",
                "mock-model"
            )));
        String token = registerAndLogin("rag-grounding-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Answer Grounding Material",
            """
            Contents
            Chapter 1 Overview
            Chapter 2 SQL Query
            Chapter 3 Index Optimization

            Chapter 1 Overview
            INTRO_MARKER This part only introduces database basics.

            Chapter 2 SQL Query
            BODY_SQL_JOIN_MARKER JOIN connects rows from multiple tables by matching keys and conditions.

            Chapter 3 Index Optimization
            INDEX_MARKER BTree indexes improve lookup speed.
            """
        );
        Long indexChunkId = findChunkIdContaining(getChunks(token, materialId), "INDEX_MARKER");

        JsonNode answer = chat(token, "What does Chapter 2 say about JOIN?", materialId, indexChunkId);

        assertAnswerContains(answer, "BODY_SQL_JOIN_MARKER");
        assertSourceContains(answer, "BODY_SQL_JOIN_MARKER");
        assertSelectedSourceHasRetrievalRoute(answer);
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

    @Test
    void structureQuestionReturnsAllFivePartsFromStudentHandbook() throws Exception {
        String token = registerAndLogin("rag-five-parts-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "学生手册",
            """
            目录
            第一部分 入学与注册
            第二部分 课程学习
            第三部分 考试与成绩
            第四部分 奖助与处分
            第五部分 毕业与离校

            第一部分 入学与注册
            PART_ONE_MARKER 学生需要按时完成报到、注册和学籍确认。

            第二部分 课程学习
            PART_TWO_MARKER 学生应按照培养方案完成课程学习和实践环节。

            第三部分 考试与成绩
            PART_THREE_MARKER 学校按考试纪律、成绩评定和复核流程管理成绩。

            第四部分 奖助与处分
            PART_FOUR_MARKER 奖学金、助学金、违纪处分和申诉都在这一部分说明。

            第五部分 毕业与离校
            PART_FIVE_MARKER 毕业资格、离校手续、档案转递和证书领取都在这一部分说明。
            """
        );
        Long laterChunkId = findChunkIdContaining(getChunks(token, materialId), "PART_FIVE_MARKER");

        JsonNode answer = chat(token, "这份学生手册有哪些部分？", materialId, laterChunkId);

        assertAnswerContains(answer, "第一部分");
        assertAnswerContains(answer, "第二部分");
        assertAnswerContains(answer, "第三部分");
        assertAnswerContains(answer, "第四部分");
        assertAnswerContains(answer, "第五部分");
    }

    @Test
    void wideStructureQuestionCanUseMoreContextButStillReturnsAtMostFiveSources() throws Exception {
        String token = registerAndLogin("rag-wide-structure-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Wide Structure Handbook",
            """
            目录
            第一部分 入学
            第二部分 课程
            第三部分 考试
            第四部分 助学
            第五部分 毕业
            第六部分 实习
            第七部分 就业

            第一部分 入学
            PART_ONE_MARKER 入学报到、注册和学籍确认在本部分说明。
            第二部分 课程
            PART_TWO_MARKER 课程学习、学分完成和选课规则在本部分说明。
            第三部分 考试
            PART_THREE_MARKER 考试纪律、成绩复核和补缓考安排在本部分说明。
            第四部分 助学
            PART_FOUR_MARKER 奖学金、助学金和资助申请在本部分说明。
            第五部分 毕业
            PART_FIVE_MARKER 毕业资格、论文要求和离校手续在本部分说明。
            第六部分 实习
            PART_SIX_MARKER 实习安排、指导教师和实习考核在本部分说明。
            第七部分 就业
            PART_SEVEN_MARKER 就业指导、校招信息和毕业去向登记在本部分说明。
            """
        );
        Long laterChunkId = findChunkIdContaining(getChunks(token, materialId), "PART_SEVEN_MARKER");

        JsonNode answer = chat(token, "这份手册有哪些部分？", materialId, laterChunkId);

        assertAnswerContains(answer, "第一部分");
        assertAnswerContains(answer, "第二部分");
        assertAnswerContains(answer, "第三部分");
        assertAnswerContains(answer, "第四部分");
        assertAnswerContains(answer, "第五部分");
        assertAnswerContains(answer, "第六部分");
        assertAnswerContains(answer, "第七部分");
        assertSourceCountAtMost(answer, 5);
        assertDistinctSourceChunks(answer);
    }

    @Test
    void partQuestionUsesRequestedPartInsteadOfOnlyChapterHeadings() throws Exception {
        String token = registerAndLogin("rag-part-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "学生手册分部分资料",
            """
            第一部分 入学与注册
            PART_ONE_MARKER 学生需要按时完成报到、注册和学籍确认。

            第二部分 课程学习
            PART_TWO_MARKER 学生应按照培养方案完成课程学习和实践环节。

            第五部分 毕业与离校
            PART_FIVE_MARKER 毕业资格、离校手续、档案转递和证书领取都在这一部分说明。
            """
        );
        Long firstChunkId = findChunkIdContaining(getChunks(token, materialId), "PART_ONE_MARKER");

        JsonNode answer = chat(token, "第五部分主要讲什么？", materialId, firstChunkId);

        assertAnswerContains(answer, "PART_FIVE_MARKER");
        assertSourceContains(answer, "PART_FIVE_MARKER");
        assertAnswerContains(answer, "第五部分");
        assertAnswerNotContains(answer, "未知页");
    }

    @Test
    void mixedStructureAndDetailQuestionStillRetrievesRequestedBodySection() throws Exception {
        String token = registerAndLogin("rag-mixed-structure-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Mixed Structure Handbook",
            """
            Contents
            Part 1 Enrollment
            Part 2 Courses
            Part 3 Exams
            Part 4 Financial Aid
            Part 5 Graduation

            Part 1 Enrollment
            PART_ONE_MARKER Enrollment, registration, and student status confirmation are explained here.

            Part 2 Courses
            PART_TWO_MARKER Course selection, credit requirements, and practical training are explained here.

            Part 3 Exams
            PART_THREE_MARKER Exam rules, grading, and review procedures are explained here.

            Part 4 Financial Aid
            PART_FOUR_MARKER Scholarships, grants, discipline, and appeals are explained here.

            Part 5 Graduation
            PART_FIVE_MARKER Graduation requirements, leaving-school procedures, archive transfer, and certificate pickup are explained here.
            """
        );
        Long laterChunkId = findChunkIdContaining(getChunks(token, materialId), "PART_FIVE_MARKER");

        JsonNode answer = chat(token, "List all parts in this handbook and explain what Part 5 covers, including archive transfer and certificate pickup.", materialId, laterChunkId);

        assertAnswerContains(answer, "Part 5");
        assertAnswerContains(answer, "archive transfer");
        assertAnswerContains(answer, "certificate pickup");
        assertSourceContains(answer, "PART_FIVE_MARKER");
        assertSelectedSourceHasRetrievalRoute(answer);
    }

    @Test
    void detailQuestionPrefersBodyChunkOverSectionHeadingAtApiLevel() throws Exception {
        String token = registerAndLogin("rag-heading-vs-body-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(
            token,
            "Heading Versus Body Material",
            """
            Part 1 Enrollment
            PART_ONE_MARKER Enrollment, registration, and student status confirmation are explained here.

            Part 5 Graduation

            Part 5 Graduation
            PART_FIVE_DETAIL_MARKER Graduation requirements, archive transfer, and certificate pickup are explained here.
            """
        );
        Long currentChunkId = findChunkIdContaining(getChunks(token, materialId), "PART_ONE_MARKER");

        JsonNode answer = chat(token, "What does Part 5 cover, including archive transfer and certificate pickup?", materialId, currentChunkId);

        assertAnswerContains(answer, "PART_FIVE_DETAIL_MARKER");
        assertSourceContains(answer, "PART_FIVE_DETAIL_MARKER");
        Assertions.assertFalse(answer.at("/sources/0/excerpt").asText().equals("Part 5 Graduation"), answer.toPrettyString());
        assertSelectedSourceHasRetrievalRoute(answer);
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

    private void assertSelectedSourceHasRetrievalRoute(JsonNode data) {
        JsonNode routes = data.at("/retrievalDebug/0/routes");
        Assertions.assertTrue(routes.isArray() && routes.size() > 0, data.toPrettyString());
        Assertions.assertFalse(data.at("/retrievalDebug/0/selectedReason").asText().isBlank(), data.toPrettyString());
    }

    private void assertSourceCountAtMost(JsonNode data, int expectedMax) {
        JsonNode sources = data.path("sources");
        Assertions.assertTrue(sources.isArray(), data.toPrettyString());
        Assertions.assertTrue(sources.size() <= expectedMax, data.toPrettyString());
    }

    private void assertDistinctSourceChunks(JsonNode data) {
        JsonNode sources = data.path("sources");
        Assertions.assertTrue(sources.isArray(), data.toPrettyString());
        java.util.Set<Long> chunkIds = new java.util.LinkedHashSet<>();
        for (JsonNode source : sources) {
            chunkIds.add(source.path("chunkId").asLong());
        }
        Assertions.assertEquals(chunkIds.size(), sources.size(), data.toPrettyString());
    }
}
