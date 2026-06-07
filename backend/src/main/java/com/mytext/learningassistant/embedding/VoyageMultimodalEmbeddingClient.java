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

public class VoyageMultimodalEmbeddingClient implements EmbeddingClient {

    private static final String INPUT_TYPE_DOCUMENT = "document";
    private static final String INPUT_TYPE_QUERY = "query";

    private final EmbeddingProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public VoyageMultimodalEmbeddingClient(EmbeddingProperties properties) {
        this(properties, new ObjectMapper());
    }

    VoyageMultimodalEmbeddingClient(EmbeddingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
    }

    @Override
    public Optional<List<Double>> embed(String text) {
        return embedDocument(text);
    }

    @Override
    public Optional<List<Double>> embedDocument(String text) {
        return embed(text, INPUT_TYPE_DOCUMENT);
    }

    @Override
    public Optional<List<Double>> embedQuery(String text) {
        return embed(text, INPUT_TYPE_QUERY);
    }

    private Optional<List<Double>> embed(String text, String inputType) {
        if (!properties.configured() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(multimodalEmbeddingsUri())
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(embeddingBody(text, inputType)))
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

    private URI multimodalEmbeddingsUri() {
        String baseUrl = properties.baseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith("/v1")) {
            return URI.create(baseUrl + "/multimodalembeddings");
        }
        return URI.create(baseUrl + "/v1/multimodalembeddings");
    }

    private String embeddingBody(String text, String inputType) throws Exception {
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("type", "text");
        textPart.put("text", text);

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("content", List.of(textPart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputs", List.of(input));
        body.put("model", properties.model());
        body.put("input_type", inputType);
        body.put("truncation", true);
        return objectMapper.writeValueAsString(body);
    }
}
