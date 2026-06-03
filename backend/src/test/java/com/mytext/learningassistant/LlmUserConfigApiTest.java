package com.mytext.learningassistant;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class LlmUserConfigApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void userCanKeepMultipleCustomModelsAfterSwitchingBackToSystemModel() throws Exception {
        String token = registerAndLogin("llm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

        saveConfig(token, "模型 A", "https://api-a.example.com", "key-a", "model-a");
        saveConfig(token, "模型 B", "https://api-b.example.com", "key-b", "model-b");

        mockMvc.perform(get("/api/llm/user-config")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.configs", hasSize(2)))
            .andExpect(jsonPath("$.data.configs[?(@.displayName=='模型 A')]").exists())
            .andExpect(jsonPath("$.data.configs[?(@.displayName=='模型 B')]").exists());

        mockMvc.perform(put("/api/llm/user-config")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "enabled": false,
                      "baseUrl": "",
                      "apiKey": "",
                      "model": ""
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.configs", hasSize(2)));

        mockMvc.perform(get("/api/llm/user-config")
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(false))
            .andExpect(jsonPath("$.data.configs", hasSize(2)));
    }

    private void saveConfig(String token, String displayName, String baseUrl, String apiKey, String model) throws Exception {
        mockMvc.perform(put("/api/llm/user-config")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "enabled": true,
                      "displayName": "%s",
                      "baseUrl": "%s",
                      "apiKey": "%s",
                      "model": "%s"
                    }
                    """.formatted(displayName, baseUrl, apiKey, model)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.enabled").value(true));
    }

    private String registerAndLogin(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "%s",
                      "password": "12345678",
                      "nickname": "LLM Test User"
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
            .andExpect(jsonPath("$.data.token").isNotEmpty())
            .andReturn();

        Matcher matcher = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(loginResult.getResponse().getContentAsString());
        if (!matcher.find()) {
            throw new IllegalStateException("token not found");
        }
        return matcher.group(1);
    }
}
