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
import org.hamcrest.Matchers;
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
 * 文本型 PDF 解析路由测试。
 *
 * <p>文本型 PDF 已经自带可选中的原生文本层，前端 PDF.js 可以直接渲染划词。
 * 因此即使全局启用 MinerU，也应该跳过外部 MinerU 进程，避免大 PDF 先等待超时再回退。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MaterialTextPdfParserTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EmbeddingClient embeddingClient;

    @DynamicPropertySource
    static void parserProperties(DynamicPropertyRegistry registry) {
        registry.add("app.document-parser.provider", () -> "mineru");
        registry.add("app.mineru.command", () -> "cmd /c exit 99");
        registry.add("app.mineru.fallback-enabled", () -> "false");
        registry.add("app.mineru.skip-text-pdf", () -> "true");
    }

    @Test
    void textPdfSkipsMineruAndKeepsSelectablePageTextLayer() throws Exception {
        org.mockito.Mockito.when(embeddingClient.embedDocument(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(List.of(0.1, 0.2, 0.3)));
        String token = registerAndLogin("text_pdf_user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "text-pdf.pdf",
            "application/pdf",
            minimalPdfWithText("Text PDF should skip MinerU and stay selectable.")
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "Text PDF")
                .param("sourceType", "PDF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/materials/" + materialId + "/pages/1/text-layer")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].text").value(Matchers.containsString("Text PDF should skip MinerU")))
            .andExpect(jsonPath("$.data[0].source").value("LEGACY"));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "文本 PDF 用户"
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
