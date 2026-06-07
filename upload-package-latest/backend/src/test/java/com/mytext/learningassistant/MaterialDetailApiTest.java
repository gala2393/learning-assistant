package com.mytext.learningassistant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class MaterialDetailApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsMaterialDetailAndChunks() throws Exception {
        String token = registerAndLogin(uniqueName("material-detail"));

        MockMultipartFile file = new MockMultipartFile(
            "file",
            "chapter-detail.txt",
            MediaType.TEXT_PLAIN_VALUE,
            "First chunk for the learning material detail API.".getBytes(StandardCharsets.UTF_8)
        );

        var uploadResult = mockMvc.perform(multipart("/api/materials")
                .file(file)
                .header("Authorization", "Bearer " + token)
                .param("title", "资料详情")
                .param("sourceType", "TXT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andReturn();

        Long materialId = extractLong(uploadResult.getResponse().getContentAsString(), "id");

        mockMvc.perform(get("/api/materials/" + materialId)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.id").value(materialId.intValue()))
            .andExpect(jsonPath("$.data.title").value("资料详情"))
            .andExpect(jsonPath("$.data.originalName").value("chapter-detail.txt"))
            .andExpect(jsonPath("$.data.sourceType").value("TXT"))
            .andExpect(jsonPath("$.data.parseStatus").value("SUCCESS"))
            .andExpect(jsonPath("$.data.summaryStatus").value("PENDING"))
            .andExpect(jsonPath("$.data.chunkCount").value(1));

        mockMvc.perform(get("/api/materials/" + materialId + "/chunks")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data[0].materialId").value(materialId.intValue()))
            .andExpect(jsonPath("$.data[0].chunkIndex").value(1))
            .andExpect(jsonPath("$.data[0].sectionTitle").value("第1切片"))
            .andExpect(jsonPath("$.data[0].chunkText").value("First chunk for the learning material detail API."));
    }

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
}
