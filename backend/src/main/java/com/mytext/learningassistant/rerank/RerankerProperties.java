package com.mytext.learningassistant.rerank;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.reranker")
public record RerankerProperties(
    boolean enabled,
    String provider,
    String baseUrl,
    String apiKey,
    String model,
    Duration timeout,
    int candidates,
    double retrievalWeight,
    double lexicalWeight
) {

    public RerankerProperties {
        provider = provider == null || provider.isBlank() ? "local" : provider.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null || model.isBlank() ? "bge-reranker-v2-m3" : model.trim();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        candidates = candidates <= 0 ? 30 : candidates;
        retrievalWeight = retrievalWeight <= 0.0 ? 0.55 : retrievalWeight;
        lexicalWeight = lexicalWeight <= 0.0 ? 0.45 : lexicalWeight;
        double total = retrievalWeight + lexicalWeight;
        if (total <= 0.0) {
            retrievalWeight = 0.55;
            lexicalWeight = 0.45;
        } else {
            retrievalWeight = retrievalWeight / total;
            lexicalWeight = lexicalWeight / total;
        }
    }

    public boolean externalConfigured() {
        return enabled && !baseUrl.isBlank() && !"local".equalsIgnoreCase(provider);
    }
}
