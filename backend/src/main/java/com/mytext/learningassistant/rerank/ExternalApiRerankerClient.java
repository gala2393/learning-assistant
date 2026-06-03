package com.mytext.learningassistant.rerank;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ExternalApiRerankerClient implements RerankerClient {

    private final RerankerProperties properties;
    private final ObjectMapper objectMapper;
    private final RerankerClient fallback;
    private final HttpClient httpClient;

    public ExternalApiRerankerClient(
        RerankerProperties properties,
        ObjectMapper objectMapper,
        RerankerClient fallback
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
    }

    @Override
    public List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates) {
        if (!properties.enabled() || query == null || query.isBlank() || candidates == null || candidates.size() < 2) {
            return List.of();
        }
        List<RerankCandidate> limited = candidates.stream()
            .limit(properties.candidates())
            .toList();
        if (limited.size() < 2) {
            return List.of();
        }
        if (!properties.externalConfigured()) {
            return fallback.rerank(query, limited);
        }
        try {
            List<RerankedCandidate> reranked = callExternalReranker(query, limited);
            return reranked.isEmpty() ? fallback.rerank(query, limited) : reranked;
        } catch (Exception ignored) {
            return fallback.rerank(query, limited);
        }
    }

    private List<RerankedCandidate> callExternalReranker(String query, List<RerankCandidate> candidates) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("query", query);
        body.put("documents", candidates.stream().map(RerankCandidate::text).toList());
        body.put("top_n", candidates.size());
        body.put("return_documents", false);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(normalizedBaseUrl() + "/rerank"))
            .timeout(properties.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        if (!properties.apiKey().isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + properties.apiKey());
        }

        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return List.of();
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            results = root.path("data");
        }
        if (!results.isArray() || results.isEmpty()) {
            return List.of();
        }

        List<RerankedCandidate> reranked = new ArrayList<>();
        for (JsonNode result : results) {
            int index = result.path("index").asInt(-1);
            if (index < 0 && result.has("document_index")) {
                index = result.path("document_index").asInt(-1);
            }
            double score = result.path("relevance_score").asDouble(Double.NaN);
            if (Double.isNaN(score)) {
                score = result.path("score").asDouble(Double.NaN);
            }
            if (index < 0 || index >= candidates.size() || Double.isNaN(score)) {
                continue;
            }
            reranked.add(new RerankedCandidate(candidates.get(index).id(), score));
        }
        return reranked.stream()
            .sorted(Comparator.comparingDouble(RerankedCandidate::score).reversed())
            .toList();
    }

    private String normalizedBaseUrl() {
        String baseUrl = properties.baseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
