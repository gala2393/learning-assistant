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
class RagManualQaDiagnosticTest {

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
    void useDeterministicLocalFallback() {
        when(embeddingClient.embedQuery(anyString())).thenReturn(Optional.empty());
        when(embeddingClient.embedDocument(anyString())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean(), any())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.summarize(anyString(), anyList())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.expandQuery(anyString())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.generateHydeAnswer(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void manuallyInspectMaterialQaAnswersSourcesAndRetrievalDebug() throws Exception {
        String token = registerAndLogin("rag-manual-diagnostic-" + UUID.randomUUID());
        Long materialId = uploadTextMaterial(token, "学生手册 RAG 诊断资料", diagnosticMaterial());
        JsonNode chunks = getChunks(token, materialId);
        Long currentLaterChunkId = findChunkIdContaining(chunks, "PART_FIVE_MARKER");

        JsonNode directoryAnswer = chat(token, "这份学生手册有哪些部分？", materialId, currentLaterChunkId);
        printCase("目录页问答", directoryAnswer);
        assertAnswerContainsAll(directoryAnswer, "第一部分", "第二部分", "第三部分", "第四部分", "第五部分");
        assertAnySourceContains(directoryAnswer, "目录");
        assertTopDebugContainsAny(directoryAnswer, 5, "目录", "第一部分", "第二部分", "第三部分", "第四部分", "第五部分");

        JsonNode chapterAnswer = chat(token, "第三部分考试与成绩主要讲什么？", materialId, currentLaterChunkId);
        printCase("章节内容问答", chapterAnswer);
        assertAnswerContainsAll(chapterAnswer, "PART_THREE_MARKER", "考试纪律", "成绩评定");
        assertTopSourceContains(chapterAnswer, "PART_THREE_MARKER");
        assertTopDebugContainsAny(chapterAnswer, 5, "PART_THREE_MARKER", "考试纪律", "成绩评定", "第三部分");
        assertAnswerNotContains(chapterAnswer, "PART_FIVE_MARKER");

        JsonNode keywordAnswer = chat(token, "学分预警是什么？", materialId, currentLaterChunkId);
        printCase("关键词问答", keywordAnswer);
        assertAnswerContainsAll(keywordAnswer, "CREDIT_WARNING_MARKER", "学分预警");
        assertTopSourceContains(keywordAnswer, "CREDIT_WARNING_MARKER");
        assertTopDebugContainsAny(keywordAnswer, 5, "CREDIT_WARNING_MARKER", "学分预警");
        assertAnswerNotContains(keywordAnswer, "PART_FIVE_MARKER");

        JsonNode unrelatedAnswer = chat(token, "量子隧穿效应的数学推导是什么？", materialId, currentLaterChunkId);
        printCase("资料无关问答", unrelatedAnswer);
        assertAnswerContainsAll(unrelatedAnswer, "当前资料里没有检索到足够依据");
        Assertions.assertEquals(0, unrelatedAnswer.path("sources").size(), unrelatedAnswer.toPrettyString());
    }

    private String diagnosticMaterial() {
        return """
            目录
            第一部分 入学与注册
            第二部分 课程学习
            第三部分 考试与成绩
            第四部分 奖助与处分
            第五部分 毕业与离校

            第一部分 入学与注册
            PART_ONE_MARKER 学生需要按时报到、完成注册，并进行学籍确认。

            第二部分 课程学习
            PART_TWO_MARKER 学生应按照培养方案完成课程学习、实践环节和选课确认。
            CREDIT_WARNING_MARKER 学分预警是指学生累计获得学分低于培养方案进度要求时，学院提醒学生调整学习计划并接受指导。

            第三部分 考试与成绩
            PART_THREE_MARKER 本部分说明考试纪律、成绩评定、成绩复核、补考缓考和诚信考试要求。
            学生如对成绩有疑问，应在规定时间内申请复核，学院按流程反馈处理结果。

            第四部分 奖助与处分
            PART_FOUR_MARKER 奖学金、助学金、违纪处分和申诉都在这一部分说明。

            第五部分 毕业与离校
            PART_FIVE_MARKER 毕业资格、离校手续、档案转递和证书领取都在这一部分说明。
            """;
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
            "rag-diagnostic.txt",
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
                      "displayName": "RAG Manual Diagnostic User"
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

    private void printCase(String name, JsonNode data) {
        System.out.println("\n=== RAG MANUAL DIAGNOSTIC: " + name + " ===");
        System.out.println("QUESTION: " + data.path("question").asText());
        System.out.println("ANSWER: " + data.path("answer").asText());
        System.out.println("SOURCES: " + data.path("sources").toPrettyString());
        System.out.println("RETRIEVAL DEBUG TOP 5: " + firstDebugEntries(data, 5).toPrettyString());
    }

    private JsonNode firstDebugEntries(JsonNode data, int limit) {
        var array = objectMapper.createArrayNode();
        JsonNode debug = data.path("retrievalDebug");
        for (int i = 0; i < Math.min(limit, debug.size()); i++) {
            array.add(debug.get(i));
        }
        return array;
    }

    private void assertAnswerContainsAll(JsonNode data, String... expectedTexts) {
        for (String expected : expectedTexts) {
            Assertions.assertTrue(data.path("answer").asText().contains(expected), data.toPrettyString());
        }
    }

    private void assertAnswerNotContains(JsonNode data, String forbidden) {
        Assertions.assertFalse(data.path("answer").asText().contains(forbidden), data.toPrettyString());
    }

    private void assertTopSourceContains(JsonNode data, String expected) {
        Assertions.assertTrue(data.at("/sources/0/excerpt").asText().contains(expected), data.toPrettyString());
    }

    private void assertAnySourceContains(JsonNode data, String expected) {
        for (JsonNode source : data.path("sources")) {
            if (source.path("excerpt").asText().contains(expected)) {
                return;
            }
        }
        Assertions.fail("source does not contain " + expected + ": " + data.toPrettyString());
    }

    private void assertTopDebugContainsAny(JsonNode data, int limit, String... expectedTexts) {
        StringBuilder combined = new StringBuilder();
        JsonNode debug = data.path("retrievalDebug");
        for (int i = 0; i < Math.min(limit, debug.size()); i++) {
            combined.append(debug.get(i).path("excerpt").asText()).append('\n');
        }
        for (String expected : expectedTexts) {
            if (combined.toString().contains(expected)) {
                return;
            }
        }
        Assertions.fail("top debug entries are not relevant: " + firstDebugEntries(data, limit).toPrettyString());
    }
}
