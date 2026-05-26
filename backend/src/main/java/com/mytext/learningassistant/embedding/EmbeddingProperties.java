package com.mytext.learningassistant.embedding;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(
    boolean enabled,
    String baseUrl,
    String apiKey,
    String model,
    Duration timeout,
    int topK,
    double scoreThreshold
) {

    public EmbeddingProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        topK = topK <= 0 ? 5 : topK;
        scoreThreshold = scoreThreshold <= 0.0 ? 0.55 : scoreThreshold;
    }

    public boolean configured() {
        return enabled
            && !baseUrl.isBlank()
            && !apiKey.isBlank()
            && !model.isBlank();
    }
}
