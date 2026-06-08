package com.mytext.learningassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import com.mytext.learningassistant.embedding.EmbeddingClient;
import com.mytext.learningassistant.material.MaterialChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
/**
 * 学习资料（Material）API 集成测试。
 * <p>
 * 覆盖范围：资料上传（TXT/PDF/DOCX/PPTX）、文本切片提取、多编码兼容
 * （UTF-8/UTF-16/GB18030）、PDF 多页与空白页处理、OCR 扫描页识别、
 * DOCX 样式清理与表格/图片提取、PPTX 幻灯片标记、资料元数据更新与删除、
 * 分片上传流程（含校验和验证与文件大小限制）、嵌入向量存储、旧版 Office 格式拒绝等。
 * <p>
 * 使用 MockBean 模拟 EmbeddingClient，避免调用真实嵌入服务。
 */
class MaterialApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MaterialChunkRepository materialChunkRepository;

    @MockBean
    private EmbeddingClient embeddingClient;

    /** 每个测试前设置 EmbeddingClient 默认返回空（不生成嵌入向量） */
    @BeforeEach
    void defaultEmbeddingStub() {
        org.mockito.Mockito.when(embeddingClient.embedDocument(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
    }

    /**
     * 测试场景：上传纯文本资料并在资料列表中查看。
     * 预期结果：上传成功返回 parseStatus=SUCCESS，列表中可查到该资料。
     */
    @Test
    void uploadsTextMaterialAndListsIt() throws Exception {
        String token = registerAndLogin(uniqueName("material-user"));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "chapter1.txt",
            MediaType.TEXT_PLAIN_VALUE,
            "Spring Boot makes course helper APIs easy.\nRAG answers based on materials.".getBytes(StandardCharsets.UTF_8)
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "课程资料1")
                .param("sourceType", "TXT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.title").value("课程资料1"))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.chunkCount").value(1))
            .andReturn();

        String uploadBody = uploadResult.getResponse().getContentAsString();
        Long materialId = extractLong(uploadBody, "id");

        mockMvc.perform(get("/api/materials")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].id").value(materialId.intValue()))
            .andExpect(jsonPath("$.data[0].title").value("课程资料1"))
            .andExpect(jsonPath("$.data[0].parseStatus").value("SUCCESS"));
    }

    /**
     * 测试场景：上传文本资料并验证嵌入向量正确存储，且不影响切片读取 API。
     * 预期结果：切片包含正确的文本、层级路径、摘要和关键词，嵌入向量 JSON 与预期一致。
     */
    @Test
    void uploadsTextMaterialAndStoresEmbeddingWithoutBreakingChunkReadApi() throws Exception {
        org.mockito.Mockito.when(embeddingClient.embedDocument(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(List.of(0.11, 0.22, 0.33)));
        String token = registerAndLogin(uniqueName("embedding-material-user"));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "embedding.txt",
            MediaType.TEXT_PLAIN_VALUE,
            "Semantic retrieval for study materials.".getBytes(StandardCharsets.UTF_8)
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Embedding TXT")
                .param("sourceType", "TXT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].chunkText").value("Semantic retrieval for study materials."))
            .andExpect(jsonPath("$.data[0].hierarchyPath").value("Embedding TXT > 第1切片 > 切片1"))
            .andExpect(jsonPath("$.data[0].summary").value("Semantic retrieval for study materials."))
            .andExpect(jsonPath("$.data[0].keywords").value(org.hamcrest.Matchers.containsString("semantic")))
            .andExpect(jsonPath("$.data[0].keywords").value(org.hamcrest.Matchers.containsString("retrieval")));

        String embeddingJson = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId).get(0).getEmbeddingJson();
        org.junit.jupiter.api.Assertions.assertEquals("[0.11,0.22,0.33]", embeddingJson);
    }

    /**
     * 测试场景：上传 UTF-16 编码的中文文本资料。
     * 预期结果：系统自动检测编码并正确解析，切片内容与原文一致。
     */
    @Test
    void uploadsUtf16TextMaterial() throws Exception {
        String token = registerAndLogin(uniqueName("utf16-material-user"));
        String text = "这是一份 UTF-16 编码的 TXT 资料，用于测试导入解析。";
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "utf16-note.txt",
            MediaType.TEXT_PLAIN_VALUE,
            text.getBytes(StandardCharsets.UTF_16)
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "UTF16 TXT")
                .param("sourceType", "TXT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].chunkText").value(text));
    }

    /**
     * 测试场景：上传 GB18030 编码的中文文本资料，并通过文件票据下载原始文件。
     * 预期结果：切片内容正确；文件下载响应的 Content-Type 包含 charset=GB18030，
     *           文件字节与上传时一致（支持重复下载）。
     */
    @Test
    void uploadsGb18030TextMaterial() throws Exception {
        String token = registerAndLogin(uniqueName("gb18030-material-user"));
        String text = "这是一份 GB18030 编码的 TXT 资料，用于测试导入解析。";
        byte[] fileBytes = text.getBytes(Charset.forName("GB18030"));
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "gb18030-note.txt",
            MediaType.TEXT_PLAIN_VALUE,
            fileBytes
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "GB18030 TXT")
                .param("sourceType", "TXT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].chunkText").value(text));

        var ticketResult = mockMvc.perform(post("/api/materials/" + materialId + "/file-ticket")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ticket").isNotEmpty())
            .andReturn();

        String ticket = extractString(ticketResult.getResponse().getContentAsString(), "ticket");
        for (int i = 0; i < 2; i += 1) {
            mockMvc.perform(get("/api/materials/" + materialId + "/file")
                    .param("ticket", ticket))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("charset=GB18030")))
                .andExpect(result -> org.junit.jupiter.api.Assertions.assertArrayEquals(
                    fileBytes,
                    result.getResponse().getContentAsByteArray()
                ));
        }
    }

    /**
     * 测试场景：上传含可提取文本的 PDF 资料。
     * 预期结果：正确提取文本切片，生成页面图片（page-1.png），支持预览文件下载。
     */
    @Test
    void uploadsPdfMaterialAndExtractsTextChunks() throws Exception {
        String token = registerAndLogin(uniqueName("pdf-material-user"));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "rag-note.pdf",
            "application/pdf",
            minimalPdfWithText("RAG PDF import parses readable course material text.")
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "PDF Note")
                .param("sourceType", "PDF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sourceType").value("PDF"))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].chunkText").value("RAG PDF import parses readable course material text."));

        mockMvc.perform(get("/api/materials/" + materialId + "/pages")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].pageNo").value(1))
            .andExpect(jsonPath("$.data[0].imageName").value("page-1.png"))
            .andExpect(jsonPath("$.data[0].renderStatus").value("PENDING"));

        mockMvc.perform(get("/api/materials/" + materialId + "/images/page-1.png")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                result.getResponse().getContentAsByteArray().length > 0
            ));

        var ticketResult = mockMvc.perform(post("/api/materials/" + materialId + "/file-ticket")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ticket").isNotEmpty())
            .andReturn();
        String ticket = extractString(ticketResult.getResponse().getContentAsString(), "ticket");
        mockMvc.perform(get("/api/materials/" + materialId + "/preview-file")
                .param("ticket", ticket))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("application/pdf")))
            .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                result.getResponse().getContentAsByteArray().length > 0
            ));
    }

    /**
     * 测试场景：上传无可提取文本的空白 PDF。
     * 预期结果：解析成功，切片提示"暂无可抽取文本"，页面图片仍可正常渲染和访问。
     */
    @Test
    void uploadsPdfWithoutExtractableTextAndStillServesLazyPreview() throws Exception {
        String token = registerAndLogin(uniqueName("blank-pdf-material-user"));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "blank-page.pdf",
            "application/pdf",
            minimalBlankPdf()
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Blank PDF")
                .param("sourceType", "PDF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sourceType").value("PDF"))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.previewStatus").value("READY"))
            .andExpect(jsonPath("$.data.pageCount").value(1))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].pageNo").value(1))
            .andExpect(jsonPath("$.data[0].chunkText").value(org.hamcrest.Matchers.containsString("暂无可抽取文本")));

        mockMvc.perform(get("/api/materials/" + materialId + "/pages")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].pageNo").value(1))
            .andExpect(jsonPath("$.data[0].imageName").value("page-1.png"));

        mockMvc.perform(get("/api/materials/" + materialId + "/images/page-1.png")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                result.getResponse().getContentAsByteArray().length > 0
            ));
    }

    /**
     * 测试场景：上传扫描版 PDF（无文本层），触发 OCR 识别。
     * 预期结果：切片包含 OCR 识别文本和图片引用标记，页面渲染状态为 READY。
     * <p>需要设置环境变量 TEST_OCR_ENABLED=true 才会执行此测试。</p>
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "TEST_OCR_ENABLED", matches = "true")
    void uploadsScannedPdfAndIndexesOcrTextByPage() throws Exception {
        String token = registerAndLogin(uniqueName("ocr-pdf-material-user"));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "scanned-page.pdf",
            "application/pdf",
            minimalBlankPdf()
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Scanned PDF")
                .param("sourceType", "PDF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.pageCount").value(1))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].pageNo").value(1))
            .andExpect(jsonPath("$.data[0].chunkText").value(org.hamcrest.Matchers.containsString("OCR_SCANNED_PAGE_TEXT")))
            .andExpect(jsonPath("$.data[0].chunkText").value(org.hamcrest.Matchers.containsString("[[material-image:page-1.png]]")));

        mockMvc.perform(get("/api/materials/" + materialId + "/pages")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].renderStatus").value("READY"));
    }

    /**
     * 测试场景：上传两页 PDF，验证每页文本关联正确的页码。
     * 预期结果：第 1 页的切片 pageNo=1 且包含 PAGE_ONE_MARKER，
     *           第 2 页的切片 pageNo=2 且包含 PAGE_TWO_MARKER。
     */
    @Test
    void uploadsPdfMaterialAndKeepsRealPageNumbersForTwoPages() throws Exception {
        String token = registerAndLogin(uniqueName("pdf-page-material-user"));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "two-page-note.pdf",
            "application/pdf",
            minimalTwoPagePdfWithText("PAGE_ONE_MARKER introduction", "PAGE_TWO_MARKER details")
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Two Page PDF Note")
                .param("sourceType", "PDF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sourceType").value("PDF"))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        String body = chunksResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(
            body.matches("(?s).*\"pageNo\"\\s*:\\s*1.*PAGE_ONE_MARKER.*"),
            body
        );
        org.junit.jupiter.api.Assertions.assertTrue(
            body.matches("(?s).*\"pageNo\"\\s*:\\s*2.*PAGE_TWO_MARKER.*"),
            body
        );
    }

    /**
     * 测试场景：对已上传的资料触发重新解析。
     * 预期结果：返回 parseStatus=SUCCESS、summaryStatus=PENDING，切片内容重新生成。
     */
    @Test
    void reparsesExistingMaterialFromOriginalFile() throws Exception {
        String token = registerAndLogin(uniqueName("material-reparse-user"));
        Long materialId = uploadTextMaterial(token, "Reparse title");

        mockMvc.perform(post("/api/materials/" + materialId + "/reparse")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").value(materialId.intValue()))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.summaryStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.chunkCount").value(1));

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].chunkText").value("A searchable material for CRUD tests."));
    }

    /**
     * 测试场景：上传 DOCX 文件，验证提取的文本不包含 Word XML 样式标签。
     * 预期结果：切片文本为纯内容（"第一段正文\n第二段正文"），
     *           响应体中不包含 w:autoSpaceDE、w:textAlignment 等 XML 标签。
     */
    @Test
    void uploadsDocxWithoutLeakingWordStyleXml() throws Exception {
        String token = registerAndLogin(uniqueName("docx-material-user"));
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "word-note.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            minimalDocxWithText("第一段", "正文", "第二段正文")
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Word Note")
                .param("sourceType", "DOCX"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sourceType").value("DOCX"))
            .andExpect(jsonPath("$.data.previewStatus").value("DEGRADED"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].chunkText").value("第一段正文\n第二段正文"))
            .andReturn();

        String body = chunksResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("<w:autoSpaceDE"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("<w:textAlignment"));
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("<w:bookmarkStart"));
    }

    /**
     * 测试场景：上传含表格的 DOCX 文件。
     * 预期结果：切片同时包含段落文本和表格内容（单元格文本用空格连接）。
     */
    @Test
    void uploadsDocxMaterialAndExtractsTableContentIntoChunks() throws Exception {
        String token = registerAndLogin(uniqueName("docx-table-material-user"));
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "word-table-note.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            minimalDocxWithTextAndTable(
                "Paragraph before table.",
                new String[][] {
                    { "Concept", "Meaning" },
                    { "RAG", "Retrieval augmented generation" }
                }
            )
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Word Table Note")
                .param("sourceType", "DOCX"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sourceType").value("DOCX"))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        String body = chunksResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Paragraph before table."), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Concept Meaning"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("RAG Retrieval augmented generation"), body);
    }

    /**
     * 测试场景：上传含内嵌图片的 DOCX 文件。
     * 预期结果：切片中包含图片引用标记 [[material-image:xxx]]，
     *           通过图片接口可下载对应的 PNG 图片。
     */
    @Test
    void uploadsDocxMaterialAndServesExtractedImages() throws Exception {
        String token = registerAndLogin(uniqueName("docx-image-material-user"));
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "word-image-note.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            minimalDocxWithImage()
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Word Image Note")
                .param("sourceType", "DOCX"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");
        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();

        String body = chunksResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\\[\\[material-image:([^\\]]+)\\]\\]").matcher(body);
        org.junit.jupiter.api.Assertions.assertTrue(matcher.find(), body);
        String imageName = matcher.group(1);

        mockMvc.perform(get("/api/materials/" + materialId + "/images/" + imageName)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "image/png"))
            .andExpect(result -> org.junit.jupiter.api.Assertions.assertTrue(
                result.getResponse().getContentAsByteArray().length > 0
            ));
    }

    /**
     * 测试场景：上传 PPTX 文件，验证切片按幻灯片页码分隔。
     * 预期结果：切片包含各幻灯片文本，且 pageNo 按 1、2 分别标记。
     */
    @Test
    void uploadsPptxMaterialAndMarksSlidesInChunks() throws Exception {
        String token = registerAndLogin(uniqueName("pptx-slide-material-user"));
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "slides-note.pptx",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            minimalPptxWithSlides("Opening slide title", "Second slide evidence")
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Slides Note")
                .param("sourceType", "PPTX"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.sourceType").value("PPTX"))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        var chunksResult = mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        String body = chunksResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Opening slide title"), body);
        org.junit.jupiter.api.Assertions.assertTrue(body.contains("Second slide evidence"), body);
        org.junit.jupiter.api.Assertions.assertTrue(
            body.matches("(?is).*(slide|page).*1.*") || body.matches("(?s).*\"pageNo\"\\s*:\\s*1.*"),
            body
        );
        org.junit.jupiter.api.Assertions.assertTrue(
            body.matches("(?is).*(slide|page).*2.*") || body.matches("(?s).*\"pageNo\"\\s*:\\s*2.*"),
            body
        );
    }

    /**
     * 测试场景：分片上传流程（不带校验和）。
     * 预期结果：创建上传会话 -> 上传分片 -> 轮询状态直到 SUCCESS，
     *           最终可通过切片接口读取上传的文本内容。
     */
    @Test
    void chunkedUploadAcceptsChunkWithoutChecksum() throws Exception {
        String token = registerAndLogin(uniqueName("chunk-upload-user"));

        var sessionResult = mockMvc.perform(post("/api/materials/upload-sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clientUploadId": "chunk-probe",
                      "title": "Chunk Probe",
                      "originalName": "chunk-probe.txt",
                      "sourceType": "TXT",
                      "sourceUrl": "",
                      "fileSize": 32,
                      "chunkSize": 5242880
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("UPLOADING"))
            .andReturn();

        String sessionId = extractString(sessionResult.getResponse().getContentAsString(), "sessionId");
        MockMultipartFile chunk = new MockMultipartFile(
            "chunk",
            "chunk-probe.txt",
            MediaType.TEXT_PLAIN_VALUE,
            "chunked upload without checksum".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/materials/upload-sessions/" + sessionId + "/chunks")
                .file(chunk)
                .header("Authorization", "Bearer " + token)
                .param("chunkIndex", "0")
                .param("totalChunks", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uploadedChunks").value(1))
            .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        Long materialId = null;
        for (int attempt = 0; attempt < 20; attempt += 1) {
            Thread.sleep(100);
            var statusResult = mockMvc.perform(get("/api/materials/upload-sessions/" + sessionId)
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
            String body = statusResult.getResponse().getContentAsString();
            if (body.contains("\"status\":\"SUCCESS\"")) {
                materialId = extractLong(body, "materialId");
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(materialId, "chunked upload did not complete");

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].chunkText").value("chunked upload without checksum"));
    }

    /**
     * 测试场景：分片上传时文件大小超过限制。
     * 预期结果：创建上传会话返回 400，错误消息包含 "too large"。
     */
    @Test
    void rejectsChunkedUploadSessionOverMaterialLimit() throws Exception {
        String token = registerAndLogin(uniqueName("chunk-upload-limit-user"));

        mockMvc.perform(post("/api/materials/upload-sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clientUploadId": "chunk-limit-probe",
                      "title": "Chunk Limit Probe",
                      "originalName": "too-large.pdf",
                      "sourceType": "PDF",
                      "sourceUrl": "",
                      "fileSize": 524288001,
                      "chunkSize": 5242880
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("too large")));
    }

    /**
     * 测试场景：分片上传时最终文件的 SHA-256 校验和与预期不匹配。
     * 预期结果：上传会话状态变为 FAILED，提示 "file checksum mismatch"；
     *           使用相同 clientUploadId 重试会创建新的会话（uploadedChunks=0）。
     */
    @Test
    void chunkedUploadFailsWhenFinalChecksumMismatches() throws Exception {
        String token = registerAndLogin(uniqueName("chunk-upload-checksum-user"));
        byte[] expectedBytes = "expected checksum content".getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = "different checksum body".getBytes(StandardCharsets.UTF_8);

        var sessionResult = mockMvc.perform(post("/api/materials/upload-sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clientUploadId": "chunk-checksum-probe",
                      "title": "Chunk Checksum Probe",
                      "originalName": "checksum-probe.txt",
                      "sourceType": "TXT",
                      "sourceUrl": "",
                      "fileSize": %d,
                      "chunkSize": 5242880,
                      "checksumSha256": "%s"
                    }
                    """.formatted(actualBytes.length, sha256(expectedBytes))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("UPLOADING"))
            .andReturn();

        String sessionId = extractString(sessionResult.getResponse().getContentAsString(), "sessionId");
        MockMultipartFile chunk = new MockMultipartFile(
            "chunk",
            "checksum-probe.txt",
            MediaType.TEXT_PLAIN_VALUE,
            actualBytes
        );

        mockMvc.perform(multipart("/api/materials/upload-sessions/" + sessionId + "/chunks")
                .file(chunk)
                .header("Authorization", "Bearer " + token)
                .param("chunkIndex", "0")
                .param("totalChunks", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        String finalBody = null;
        for (int attempt = 0; attempt < 20; attempt += 1) {
            Thread.sleep(100);
            var statusResult = mockMvc.perform(get("/api/materials/upload-sessions/" + sessionId)
                    .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
            finalBody = statusResult.getResponse().getContentAsString();
            if (finalBody.contains("\"status\":\"FAILED\"")) {
                break;
            }
        }
        org.junit.jupiter.api.Assertions.assertNotNull(finalBody, "upload session status was not returned");
        org.junit.jupiter.api.Assertions.assertTrue(finalBody.contains("\"status\":\"FAILED\""), finalBody);
        org.junit.jupiter.api.Assertions.assertTrue(finalBody.contains("file checksum mismatch"), finalBody);

        var retrySessionResult = mockMvc.perform(post("/api/materials/upload-sessions")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "clientUploadId": "chunk-checksum-probe",
                      "title": "Chunk Checksum Probe",
                      "originalName": "checksum-probe.txt",
                      "sourceType": "TXT",
                      "sourceUrl": "",
                      "fileSize": %d,
                      "chunkSize": 5242880,
                      "checksumSha256": "%s"
                    }
                    """.formatted(actualBytes.length, sha256(expectedBytes))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("UPLOADING"))
            .andExpect(jsonPath("$.data.uploadedChunks").value(0))
            .andReturn();

        String retrySessionId = extractString(retrySessionResult.getResponse().getContentAsString(), "sessionId");
        org.junit.jupiter.api.Assertions.assertNotEquals(sessionId, retrySessionId);
    }

    /**
     * 测试场景：上传当前仍不支持的旧版 PPT 文件。
     * 预期结果：返回 400，错误消息提示需要另存为 .pptx。
     */
    @Test
    void rejectsLegacyOfficeFormatsWithClearMessage() throws Exception {
        String token = registerAndLogin(uniqueName("legacy-office-user"));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "legacy.ppt",
            "application/vnd.ms-powerpoint",
            "legacy binary office content".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
            .param("title", "Legacy PowerPoint")
            .param("sourceType", "PPT"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(".pptx")));
    }

    /**
     * 测试场景：更新已有资料的元数据（标题和来源 URL）。
     * 预期结果：更新接口返回新标题，再次查询确认标题和 sourceUrl 已更改。
     */
    @Test
    void updatesMaterialMetadata() throws Exception {
        String token = registerAndLogin(uniqueName("material-update-user"));
        Long materialId = uploadTextMaterial(token, "Before title");

        mockMvc.perform(put("/api/materials/" + materialId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "title": "After title",
                      "sourceUrl": "https://example.com/after"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.title").value("After title"));

        mockMvc.perform(get("/api/materials/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.title").value("After title"))
            .andExpect(jsonPath("$.data.sourceUrl").value("https://example.com/after"));
    }

    /**
     * 测试场景：删除资料及其关联的切片。
     * 预期结果：删除接口返回成功，再次查询资料或切片均返回 404。
     */
    @Test
    void deletesMaterialAndItsChunks() throws Exception {
        String token = registerAndLogin(uniqueName("material-delete-user"));
        Long materialId = uploadTextMaterial(token, "Delete title");

        mockMvc.perform(delete("/api/materials/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/materials/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isNotFound());
    }

    // ========== 辅助方法 ==========

    /** 注册并登录用户，返回认证 token */
    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "资料用户"
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

    /** 上传一份标准纯文本资料，返回资料 ID */
    private Long uploadTextMaterial(String token, String title) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "chapter.txt",
            MediaType.TEXT_PLAIN_VALUE,
            "A searchable material for CRUD tests.".getBytes(StandardCharsets.UTF_8)
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", title)
                .param("sourceType", "TXT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        return extractLong(uploadResult.getResponse().getContentAsString(), "id");
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

    /** 计算字节数组的 SHA-256 哈希值（十六进制格式） */
    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    /** 构造含指定文本的最小 PDF 字节数组 */
    private byte[] minimalPdfWithText(String text) {
        String escapedText = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
        String object1 = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
        String object2 = "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n";
        String object3 = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n";
        String stream = "BT\n/F1 12 Tf\n72 720 Td\n(" + escapedText + ") Tj\nET\n";
        String object4 = "4 0 obj\n<< /Length " + stream.getBytes(StandardCharsets.US_ASCII).length + " >>\nstream\n" + stream + "endstream\nendobj\n";
        String object5 = "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n";
        String[] objects = { object1, object2, object3, object4, object5 };
        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> offsets = new java.util.ArrayList<>();
        for (String object : objects) {
            offsets.add(pdf.toString().getBytes(StandardCharsets.US_ASCII).length);
            pdf.append(object);
        }
        int xrefOffset = pdf.toString().getBytes(StandardCharsets.US_ASCII).length;
        pdf.append("xref\n0 6\n");
        pdf.append("0000000000 65535 f \n");
        for (Integer offset : offsets) {
            pdf.append("%010d 00000 n \n".formatted(offset));
        }
        pdf.append("trailer\n<< /Root 1 0 R /Size 6 >>\n");
        pdf.append("startxref\n").append(xrefOffset).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** 构造无文本的空白 PDF 字节数组 */
    private byte[] minimalBlankPdf() {
        String object1 = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
        String object2 = "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n";
        String object3 = "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>\nendobj\n";
        String[] objects = { object1, object2, object3 };
        return buildPdf(objects, 4);
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

    /** 构造含指定文本段落和 Word XML 样式标签的最小 DOCX 字节数组 */
    private byte[] minimalDocxWithText(String firstTextRun, String secondTextRun, String secondText) throws Exception {
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:pPr>
                    <w:autoSpaceDE/>
                    <w:autoSpaceDN/>
                    <w:textAlignment w:val="auto"/>
                    <w:rPr><w:rFonts w:ascii="微软雅黑" w:eastAsia="微软雅黑"/></w:rPr>
                  </w:pPr>
                  <w:bookmarkStart w:id="0" w:name="_GoBack"/>
                  <w:r><w:t>%s</w:t></w:r>
                  <w:r><w:t>%s</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:t xml:space="preserve">%s</w:t></w:r>
                </w:p>
              </w:body>
            </w:document>
            """.formatted(firstTextRun, secondTextRun, secondText);
        documentXml = documentXml.replaceAll(">\\s+<", "><");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(documentXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }

    /** 构造含段落和表格的最小 DOCX 字节数组 */
    private byte[] minimalDocxWithTextAndTable(String paragraphText, String[][] rows) throws Exception {
        StringBuilder tableXml = new StringBuilder("<w:tbl>");
        for (String[] row : rows) {
            tableXml.append("<w:tr>");
            for (String cell : row) {
                tableXml.append("<w:tc><w:p><w:r><w:t>")
                    .append(escapeXml(cell))
                    .append("</w:t></w:r></w:p></w:tc>");
            }
            tableXml.append("</w:tr>");
        }
        tableXml.append("</w:tbl>");
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>%s</w:t></w:r></w:p>
                %s
              </w:body>
            </w:document>
            """.formatted(escapeXml(paragraphText), tableXml);
        return zipEntries(Map.of(
            "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                </Types>
                """,
            "word/document.xml", documentXml.replaceAll(">\\s+<", "><")
        ));
    }

    /** 构造含内嵌图片的最小 DOCX 字节数组 */
    private byte[] minimalDocxWithImage() throws Exception {
        String documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                        xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                        xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Image paragraph.</w:t></w:r></w:p>
                <w:p><w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>
              </w:body>
            </w:document>
            """.replaceAll(">\\s+<", "><");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("[Content_Types].xml", """
            <?xml version="1.0" encoding="UTF-8"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="xml" ContentType="application/xml"/>
              <Default Extension="png" ContentType="image/png"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """.getBytes(StandardCharsets.UTF_8));
        entries.put("word/document.xml", documentXml.getBytes(StandardCharsets.UTF_8));
        entries.put("word/media/image1.png", Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lrW9ewAAAABJRU5ErkJggg=="));
        return zipEntriesBytes(entries);
    }

    /** 构造含多页幻灯片的最小 PPTX 字节数组 */
    private byte[] minimalPptxWithSlides(String firstSlideText, String secondSlideText) throws Exception {
        return zipEntries(Map.of(
            "[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                  <Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
                </Types>
                """,
            "ppt/slides/slide1.xml", slideXml(firstSlideText),
            "ppt/slides/slide2.xml", slideXml(secondSlideText)
        ));
    }

    /** 生成 PPTX 单页幻灯片 XML */
    private String slideXml(String text) {
        return """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
                   xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <p:cSld>
                <p:spTree>
                  <p:sp>
                    <p:txBody>
                      <a:p><a:r><a:t>%s</a:t></a:r></a:p>
                    </p:txBody>
                  </p:sp>
                </p:spTree>
              </p:cSld>
            </p:sld>
            """.formatted(escapeXml(text)).replaceAll(">\\s+<", "><");
    }

    /** 将字符串键值对打包为 ZIP 字节数组 */
    private byte[] zipEntries(Map<String, String> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /** 将字节数组键值对打包为 ZIP 字节数组 */
    private byte[] zipEntriesBytes(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    /** 对 XML 特殊字符进行转义 */
    private String escapeXml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
