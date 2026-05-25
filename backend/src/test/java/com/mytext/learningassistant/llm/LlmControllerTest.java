package com.mytext.learningassistant.llm;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "app.llm.enabled=true",
    "app.llm.base-url=https://openrouter.ai/api",
    "app.llm.api-key=test-key",
    "app.llm.model=deepseek/deepseek-chat-v3-0324:free"
})
class LlmControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusShowsConfiguredProviderWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/llm/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.enabled").value(true))
            .andExpect(jsonPath("$.data.configured").value(true))
            .andExpect(jsonPath("$.data.apiKey").doesNotExist())
            .andExpect(jsonPath("$.data.model").value("deepseek/deepseek-chat-v3-0324:free"));
    }
}
