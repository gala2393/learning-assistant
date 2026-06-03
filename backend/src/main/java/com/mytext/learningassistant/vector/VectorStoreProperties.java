package com.mytext.learningassistant.vector;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.vector-store")
public record VectorStoreProperties(
    boolean enabled,
    String provider,
    String baseUrl,
    String apiKey,
    String collection,
    Duration timeout
) {

    public VectorStoreProperties {
        provider = provider == null || provider.isBlank() ? "qdrant" : provider.trim().toLowerCase();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        collection = collection == null || collection.isBlank() ? "learning_assistant_chunks" : collection.trim();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    public boolean qdrantConfigured() {
        return enabled && "qdrant".equals(provider) && !baseUrl.isBlank();
    }
}
