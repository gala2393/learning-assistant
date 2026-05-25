package com.mytext.learningassistant.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
    boolean enabled,
    String baseUrl,
    String apiKey,
    String model,
    Duration timeout
) {

    public LlmProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
    }

    public boolean configured() {
        return enabled
            && !baseUrl.isBlank()
            && !apiKey.isBlank()
            && !model.isBlank();
    }
}
