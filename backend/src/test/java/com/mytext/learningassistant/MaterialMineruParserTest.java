package com.mytext.learningassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mytext.learningassistant.embedding.EmbeddingClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MinerU 解析适配集成测试。
 *
 * <p>测试通过一个临时脚本模拟 MinerU CLI 输出，验证后端能消费 content_list_v2.json，
 * 并把 bbox 文本层写入 /pages/{pageNo}/text-layer 接口。这样测试不依赖真实 MinerU 安装。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaterialMineruParserTest {

    private static final Path MINERU_TEST_DIR = Path.of(System.getProperty("java.io.tmpdir"), "learning-assistant-mineru-test");
    private static Path fakeMineruCommand;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmbeddingClient embeddingClient;

    @BeforeAll
    static void prepareFakeMineru() throws Exception {
        Files.createDirectories(MINERU_TEST_DIR);
        fakeMineruCommand = MINERU_TEST_DIR.resolve("fake-mineru.cmd");
        Files.writeString(fakeMineruCommand, """
            @echo off
            set OUT=
            :loop
            if "%~1"=="" goto done
            if "%~1"=="-o" (
              set OUT=%~2
              shift
              shift
              goto loop
            )
            shift
            goto loop
            :done
            if "%OUT%"=="" exit /b 2
            mkdir "%OUT%\\result"
            > "%OUT%\\result\\mineru-note_content_list.json" echo [{"type":"text","text":"MinerU selectable text","bbox":[100,120,600,160],"page_idx":0}]
            exit /b 0
            """, StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void mineruProperties(DynamicPropertyRegistry registry) {
        registry.add("app.document-parser.provider", () -> "mineru");
        registry.add("app.mineru.command", () -> "cmd /c \"" + fakeMineruCommand + "\"");
        registry.add("app.mineru.workspace", () -> MINERU_TEST_DIR.resolve("workspace").toString());
        registry.add("app.mineru.fallback-enabled", () -> "false");
        registry.add("app.mineru.skip-text-pdf", () -> "false");
        registry.add("app.mineru.timeout", () -> "20s");
        registry.add("app.mineru.model-source", () -> "modelscope");
    }

    @Test
    void mineruOutputCreatesSelectablePageTextLayer() throws Exception {
        org.mockito.Mockito.when(embeddingClient.embedDocument(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(List.of(0.1, 0.2, 0.3)));
        String token = registerAndLogin("mineru_user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "mineru-note.pdf",
            "application/pdf",
            minimalPdfWithText("Legacy text should be replaced by MinerU output.")
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "MinerU PDF")
                .param("sourceType", "PDF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].chunkText").value("MinerU selectable text"));

        mockMvc.perform(get("/api/materials/" + materialId + "/pages/1/text-layer")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].text").value("MinerU selectable text"))
            .andExpect(jsonPath("$.data[0].source").value("MINERU"))
            .andExpect(jsonPath("$.data[0].bboxX").value(100.0))
            .andExpect(jsonPath("$.data[0].bboxY").value(120.0))
            .andExpect(jsonPath("$.data[0].bboxWidth").value(500.0))
            .andExpect(jsonPath("$.data[0].bboxHeight").value(40.0));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "MinerU 用户"
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
        java.util.ArrayList<Integer> offsets = new java.util.ArrayList<>();
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
}
