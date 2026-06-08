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

import org.junit.jupiter.api.Assertions;
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
/**
 * RAG（检索增强生成）资料问答验收测试。
 * <p>
 * 覆盖范围：基于资料的问答端到端流程，包括"当前页问答"（通过 chunkId 锚定页码）、
 * 关键词检索问答（定义查询、对比查询）、资料概览问答、无证据时的兜底回复、
 * 语义改写后的嵌入向量检索、以及通过 currentPageChunkIds 锚定阅读器页码上下文。
 * <p>
 * 使用 MockBean 模拟 EmbeddingClient 和 ThirdPartyLlmClient，不调用真实外部服务。
 */
class RagMaterialQaAcceptanceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EmbeddingClient embeddingClient;

    @MockBean
    private ThirdPartyLlmClient thirdPartyLlmClient;

    /** 每个测试前将嵌入和 LLM 客户端设置为默认返回空（走本地兜底逻辑） */
    @BeforeEach
    void useLocalFallbackByDefault() {
        when(embeddingClient.embedQuery(anyString())).thenReturn(Optional.empty());
        when(embeddingClient.embedDocument(anyString())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.answer(anyString(), anyList(), anyList(), anyBoolean(), any())).thenReturn(Optional.empty());
        when(thirdPartyLlmClient.summarize(anyString(), anyList())).thenReturn(Optional.empty());
    }

    /**
     * 综合验收场景：涵盖 RAG 资料问答的多种典型用例。
     * <p>
     * 测试场景及预期结果：
     * <ul>
     *   <li>当前页问答（chunkId 锚定）- 回答应包含第 2 页标记，不包含第 1 页内容</li>
     *   <li>定义查询（"什么是 RAG？"）- 引用摘要应包含 "RAG refers" 关键内容</li>
     *   <li>对比查询（"BM25 和 vector search 有什么区别？"）- 引用应包含对比说明</li>
     *   <li>资料概览查询 - 回答应包含 "MySQL"，引用应包含 "database fundamentals"</li>
     *   <li>无证据场景 - 当资料中无相关内容时，sources 为空数组，回复提示未检索到足够依据</li>
     * </ul>
     */
    @Test
    void materialQaAcceptanceScenarios() throws Exception {
        String token = registerAndLogin(uniqueName("rag-acceptance-user"));

        Long pageMaterialId = uploadPdfMaterial(
            token,
            "Two Page Acceptance Material",
            "PAGE_ONE_MARKER Photosynthesis converts sunlight into plant energy.",
            "PAGE_TWO_MARKER Database indexes speed lookup and filtering."
        );
        JsonNode chunks = getChunks(token, pageMaterialId);
        Long pageTwoChunkId = findChunkIdContaining(chunks, "PAGE_TWO_MARKER");

        JsonNode pageAnswer = chat(
            token,
            "\u8fd9\u4e00\u9875\u5728\u8bb2\u4ec0\u4e48\uff1f",
            pageMaterialId,
            pageTwoChunkId
        );
        printCase("current page", pageAnswer);
        assertAnswerContains(pageAnswer, "PAGE_TWO_MARKER");
        assertAnswerNotContains(pageAnswer, "PAGE_ONE_MARKER");
        assertFirstSourceChunk(pageAnswer, pageTwoChunkId);

        Long keywordMaterialId = uploadTextMaterial(
            token,
            "Keyword Acceptance Material",
            "Indexes improve database lookup performance. ".repeat(20)
                + "\n\nRAG refers to retrieval augmented generation. It combines retrieved material with model generation."
                + "\n\nBM25 and vector search are different: BM25 matches exact terms, while vector search captures semantic similarity."
        );

        JsonNode definitionAnswer = chat(token, "\u4ec0\u4e48\u662f RAG\uff1f", keywordMaterialId, null);
        printCase("definition", definitionAnswer);
        assertFirstSourceExcerptContains(definitionAnswer, "RAG refers");

        JsonNode comparisonAnswer = chat(token, "BM25\u548c vector search \u6709\u4ec0\u4e48\u533a\u522b\uff1f", keywordMaterialId, null);
        printCase("comparison", comparisonAnswer);
        assertFirstSourceExcerptContains(comparisonAnswer, "vector search are different");

        Long overviewMaterialId = uploadTextMaterial(
            token,
            "MySQL 5.7 Study Guide",
            "This book introduces MySQL 5.7 database fundamentals, including installation, SQL syntax, indexes, transactions, storage engines, replication, backup, and performance tuning. ".repeat(8)
                + "\n\nIt is intended for learners who want to understand relational database design and practical MySQL administration."
        );

        JsonNode overviewAnswer = chat(token, "\u8fd9\u662f\u4ec0\u4e48\u4e66\uff0c\u8bb2\u4ec0\u4e48\u7684\uff0c\u4ecb\u7ecd\u4e00\u4e0b", overviewMaterialId, null);
        printCase("material overview", overviewAnswer);
        assertAnswerContains(overviewAnswer, "MySQL");
        assertFirstSourceExcerptContains(overviewAnswer, "database fundamentals");

        Long plantMaterialId = uploadTextMaterial(
            token,
            "Plant Only Material",
            "Chlorophyll converts sunlight into stored chemical energy."
        );
        uploadTextMaterial(
            token,
            "Other Database Material",
            "Database indexes speed lookup and query filtering."
        );

        JsonNode noEvidenceAnswer = chat(token, "How do database indexes speed lookup and query filtering?", plantMaterialId, null);
        printCase("no evidence", noEvidenceAnswer);
        Assertions.assertEquals(0, noEvidenceAnswer.at("/sources").size(), noEvidenceAnswer.toPrettyString());
        assertAnswerContains(noEvidenceAnswer, "\u5f53\u524d\u8d44\u6599\u91cc\u6ca1\u6709\u68c0\u7d22\u5230\u8db3\u591f\u4f9d\u636e");
        assertAnswerNotContains(noEvidenceAnswer, "Other Database Material");
    }

    /**
     * 测试场景：语义改写后的查询可通过嵌入向量检索到相关资料。
     * <p>
     * 用户提问 "What is retrieval enhanced generation?"，通过 MockBean 设置
     * 嵌入向量匹配，验证系统能通过语义相似性检索到 "RAG refers to retrieval augmented generation" 切片。
     * 预期结果：第一个来源引用包含 "RAG refers to retrieval augmented generation"。
     */
    @Test
    void semanticRewriteQuestionCanUseEmbeddingRetrievalWithinCurrentMaterial() throws Exception {
        when(embeddingClient.embedQuery(org.mockito.ArgumentMatchers.contains("retrieval enhanced generation")))
            .thenReturn(Optional.of(List.of(0.9, 0.1, 0.1)));
        when(embeddingClient.embedDocument(org.mockito.ArgumentMatchers.contains("RAG refers to retrieval augmented generation")))
            .thenReturn(Optional.of(List.of(0.9, 0.1, 0.1)));
        when(embeddingClient.embedDocument(org.mockito.ArgumentMatchers.argThat(text ->
            text != null && text.contains("BM25 and vector search are different"))))
            .thenReturn(Optional.of(List.of(0.1, 0.9, 0.1)));

        String token = registerAndLogin(uniqueName("rag-embedding-user"));
        Long materialId = uploadTextMaterial(
            token,
            "Embedding Retrieval Material",
            "RAG refers to retrieval augmented generation."
                + "\n\nBM25 and vector search are different: BM25 matches exact terms, while vector search captures semantic similarity."
        );

        JsonNode answer = chat(token, "What is retrieval enhanced generation?", materialId, null);
        assertFirstSourceExcerptContains(answer, "RAG refers to retrieval augmented generation");
    }

    /**
     * 测试场景：通过 currentPageChunkIds 锚定阅读器的当前页上下文（不传 chunkId）。
     * <p>
     * 预期结果：即使不传 chunkId 参数，传入 currentPageNo=2 和对应的 chunkIds，
     *           回答仍应基于第 2 页内容，不包含第 1 页信息。
     */
    @Test
    void currentPageChunkIdsCanAnchorReaderQaEvenWithoutChunkId() throws Exception {
        String token = registerAndLogin(uniqueName("rag-page-context-user"));

        Long materialId = uploadPdfMaterial(
            token,
            "Reader Page Context Material",
            "PAGE_ONE_MARKER Photosynthesis converts sunlight into plant energy.",
            "PAGE_TWO_MARKER Database indexes speed lookup and filtering."
        );
        JsonNode chunks = getChunks(token, materialId);
        Long pageTwoChunkId = findChunkIdContaining(chunks, "PAGE_TWO_MARKER");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", "这一页讲了什么？");
        payload.put("mode", "MATERIAL");
        payload.put("materialId", materialId);
        payload.put("currentPageNo", 2);
        payload.put("currentPageChunkIds", List.of(pageTwoChunkId));

        var result = mockMvc.perform(post("/api/rag/chat")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        JsonNode answer = objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
        assertAnswerContains(answer, "PAGE_TWO_MARKER");
        assertAnswerNotContains(answer, "PAGE_ONE_MARKER");
        assertFirstSourceChunk(answer, pageTwoChunkId);
    }

    // ========== 辅助方法 ==========

    /** 发送 RAG 聊天请求并返回回答数据节点 */
    private JsonNode chat(String token, String question, Long materialId, Long chunkId) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("mode", "MATERIAL");
        payload.put("materialId", materialId);
        if (chunkId != null) {
            payload.put("chunkId", chunkId);
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

    /** 打印测试用例结果到控制台（便于调试） */
    private void printCase(String name, JsonNode data) {
        String answer = data.path("answer").asText();
        JsonNode sources = data.path("sources");
        System.out.println("\n=== QA ACCEPTANCE: " + name + " ===");
        System.out.println("QUESTION: " + data.path("question").asText());
        System.out.println("ANSWER: " + answer);
        System.out.println("SOURCES: " + sources.toPrettyString());
    }

    /** 断言回答中包含指定文本 */
    private void assertAnswerContains(JsonNode data, String expected) {
        Assertions.assertTrue(data.path("answer").asText().contains(expected), data.toPrettyString());
    }

    /** 断言回答中不包含指定文本 */
    private void assertAnswerNotContains(JsonNode data, String forbidden) {
        Assertions.assertFalse(data.path("answer").asText().contains(forbidden), data.toPrettyString());
    }

    /** 断言第一个来源引用的摘要包含指定文本 */
    private void assertFirstSourceExcerptContains(JsonNode data, String expected) {
        Assertions.assertTrue(data.at("/sources/0/excerpt").asText().contains(expected), data.toPrettyString());
    }

    /** 断言第一个来源的 chunkId 与预期匹配 */
    private void assertFirstSourceChunk(JsonNode data, Long expectedChunkId) {
        Assertions.assertEquals(expectedChunkId.longValue(), data.at("/sources/0/chunkId").asLong(), data.toPrettyString());
    }

    /** 获取指定资料的所有切片 */
    private JsonNode getChunks(String token, Long materialId) throws Exception {
        var result = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).at("/data");
    }

    /** 在切片列表中查找包含指定标记文本的切片 ID */
    private Long findChunkIdContaining(JsonNode chunks, String marker) {
        for (JsonNode chunk : chunks) {
            if (chunk.path("chunkText").asText().contains(marker)) {
                return chunk.path("id").asLong();
            }
        }
        throw new IllegalStateException("chunk containing marker not found: " + chunks.toPrettyString());
    }

    /** 上传纯文本资料，返回资料 ID */
    private Long uploadTextMaterial(String token, String title, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "acceptance.txt",
            MediaType.TEXT_PLAIN_VALUE,
            content.getBytes(StandardCharsets.UTF_8)
        );
        return uploadMaterial(token, title, "TXT", file);
    }

    /** 上传两页 PDF 资料，返回资料 ID */
    private Long uploadPdfMaterial(String token, String title, String firstPageText, String secondPageText) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "acceptance.pdf",
            "application/pdf",
            minimalTwoPagePdfWithText(firstPageText, secondPageText)
        );
        return uploadMaterial(token, title, "PDF", file);
    }

    /** 通用资料上传方法，返回资料 ID */
    private Long uploadMaterial(String token, String title, String sourceType, MockMultipartFile file) throws Exception {
        var result = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", title)
                .param("sourceType", sourceType))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();
        return extractLong(result.getResponse().getContentAsString(), "id");
    }

    /** 注册并登录用户，返回认证 token */
    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "RAG Acceptance User"
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

    /** 生成唯一名称，避免测试间数据冲突 */
    private String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 从 JSON 响应体中提取指定字符串字段 */
    private String extractString(String body, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(fieldName + " not found in response: " + body);
        }
        return matcher.group(1);
    }

    /** 从 JSON 响应体中提取指定长整型字段 */
    private Long extractLong(String body, String fieldName) {
        Matcher matcher = Pattern.compile("\"" + fieldName + "\"\\s*:\\s*(\\d+)").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException(fieldName + " not found in response: " + body);
        }
        return Long.parseLong(matcher.group(1));
    }

    /** 构造含两页文本的最小 PDF 字节数组 */
    private byte[] minimalTwoPagePdfWithText(String firstPageText, String secondPageText) {
        String firstStream = pdfTextStream(firstPageText);
        String secondStream = pdfTextStream(secondPageText);
        String object1 = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
        String object2 = "2 0 obj\n<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>\nendobj\n";
        String object3 = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 5 0 R /Resources << /Font << /F1 7 0 R >> >> >>\nendobj\n";
        String object4 = "4 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>\nendobj\n";
        String object5 = "5 0 obj\n<< /Length " + firstStream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + firstStream + "endstream\nendobj\n";
        String object6 = "6 0 obj\n<< /Length " + secondStream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + secondStream + "endstream\nendobj\n";
        String object7 = "7 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n";
        String[] objects = { object1, object2, object3, object4, object5, object6, object7 };
        return buildPdf(objects, 8);
    }

    /** 构造 PDF 文本流操作命令 */
    private String pdfTextStream(String text) {
        String escapedText = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        return "BT\n/F1 12 Tf\n72 720 Td\n(" + escapedText + ") Tj\nET\n";
    }

    /** 根据 PDF 对象数组构建完整的 PDF 字节数组 */
    private byte[] buildPdf(String[] objects, int size) {
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new java.util.ArrayList<>();
        for (String object : objects) {
            offsets.add(pdf.toString().getBytes(StandardCharsets.US_ASCII).length);
            pdf.append(object);
        }
        int xrefOffset = pdf.toString().getBytes(StandardCharsets.US_ASCII).length;
        pdf.append("xref\n0 ").append(size).append("\n");
        pdf.append("0000000000 65535 f \n");
        for (Integer offset : offsets) {
            pdf.append("%010d 00000 n \n".formatted(offset));
        }
        pdf.append("trailer\n<< /Root 1 0 R /Size ").append(size).append(" >>\n");
        pdf.append("startxref\n").append(xrefOffset).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
