package com.mytext.learningassistant.embedding;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties) {
        this(properties, new ObjectMapper());
    }

    OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
    }

    @Override
    public Optional<List<Double>> embed(String text) {
        if (!properties.configured() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(embeddingsUri())
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(embeddingBody(text)))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode embeddingNode = objectMapper.readTree(response.body())
                .path("data")
                .path(0)
                .path("embedding");
            if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
                return Optional.empty();
            }

            List<Double> embedding = new ArrayList<>(embeddingNode.size());
            for (JsonNode value : embeddingNode) {
                if (!value.isNumber()) {
                    return Optional.empty();
                }
                embedding.add(value.asDouble());
            }
            return embedding.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(embedding));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private URI embeddingsUri() {
        String baseUrl = properties.baseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith("/v1")) {
            return URI.create(baseUrl + "/embeddings");
        }
        return URI.create(baseUrl + "/v1/embeddings");
    }

    private String embeddingBody(String text) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("input", text);
        return objectMapper.writeValueAsString(body);
    }
}
