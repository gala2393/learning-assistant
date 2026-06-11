package com.mytext.learningassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mytext.learningassistant.embedding.EmbeddingClient;
import com.mytext.learningassistant.material.MaterialProcessingJobService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 大 PDF 快速导入回归测试。
 *
 * <p>生产环境会对大 PDF 走轻量导入分支。图片型 PDF 如果没有文本层，也必须按总页数
 * 创建页级 chunk，不能只保留前 80 页或因为本机缺少 pdftotext 而失败。</p>
 */
@SpringBootTest(properties = {
    "app.material-processing.scheduler-enabled=false",
    "app.pdf.large-fast-import-bytes=1",
    "app.ocr.enabled=false"
})
@AutoConfigureMockMvc
class MaterialLargePdfFastImportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MaterialProcessingJobService materialProcessingJobService;

    @MockitoBean
    private EmbeddingClient embeddingClient;

    @Test
    void imageOnlyLargePdfCreatesChunkForEveryPage() throws Exception {
        org.mockito.Mockito.when(embeddingClient.embedDocument(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(List.of(0.1, 0.2, 0.3)));
        String token = registerAndLogin("large_pdf_user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "large-image-only.pdf",
            "application/pdf",
            minimalBlankPdf(120)
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Large Image Only PDF")
                .param("sourceType", "PDF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uploadStatus").value("UPLOADED"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");
        drainMaterialJobs();

        mockMvc.perform(get("/api/materials/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.textStatus").value("PARTIAL"))
            .andExpect(jsonPath("$.data.indexStatus").value("PARTIAL"))
            .andExpect(jsonPath("$.data.ocrStatus").value("DISABLED"))
            .andExpect(jsonPath("$.data.processingStage").value("图片页已入库"));

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data", Matchers.hasSize(120)))
            .andExpect(jsonPath("$.data[0].pageNo").value(1))
            .andExpect(jsonPath("$.data[0].chunkText").value(Matchers.containsString("[[material-image:page-1.png]]")))
            .andExpect(jsonPath("$.data[119].pageNo").value(120))
            .andExpect(jsonPath("$.data[119].chunkText").value(Matchers.containsString("[[material-image:page-120.png]]")));
    }

    /** 主动驱动后台队列，避免测试依赖定时器。 */
    private void drainMaterialJobs() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            int executed = materialProcessingJobService.runReadyJobs(20);
            if (executed == 0) {
                return;
            }
            Thread.sleep(20);
        }
        org.junit.jupiter.api.Assertions.fail("material processing jobs did not drain before timeout");
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "大 PDF 用户"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk());

        var loginResult = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678"
                    }
                    """.formatted(username)))
            .andExpect(status().isOk())
            .andReturn();
        return extractString(loginResult.getResponse().getContentAsString(), "token");
    }

    private String extractString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("field not found: " + field + " in " + json);
        }
        return matcher.group(1);
    }

    private Long extractLong(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new AssertionError("field not found: " + field + " in " + json);
        }
        return Long.parseLong(matcher.group(1));
    }

    private byte[] minimalBlankPdf(int pageCount) {
        if (pageCount <= 0) {
            throw new IllegalArgumentException("pageCount must be positive");
        }
        StringBuilder kids = new StringBuilder();
        String[] pageObjects = new String[pageCount];
        for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
            int objectNo = pageIndex + 3;
            kids.append(objectNo).append(" 0 R ");
            pageObjects[pageIndex] = objectNo + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n";
        }
        String object1 = "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n";
        String object2 = "2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageCount + " >>\nendobj\n";
        String[] objects = new String[pageCount + 2];
        objects[0] = object1;
        objects[1] = object2;
        System.arraycopy(pageObjects, 0, objects, 2, pageCount);

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        int[] offsets = new int[objects.length + 1];
        offsets[0] = 0;
        for (int index = 0; index < objects.length; index++) {
            offsets[index + 1] = pdf.toString().getBytes(StandardCharsets.US_ASCII).length;
            pdf.append(objects[index]);
        }
        int xrefOffset = pdf.toString().getBytes(StandardCharsets.US_ASCII).length;
        pdf.append("xref\n0 ").append(objects.length + 1).append("\n");
        pdf.append("0000000000 65535 f \n");
        for (int index = 1; index < offsets.length; index++) {
            pdf.append(String.format("%010d 00000 n \n", offsets[index]));
        }
        pdf.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\n");
        pdf.append("startxref\n").append(xrefOffset).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
