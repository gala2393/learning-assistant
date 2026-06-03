package com.mytext.learningassistant.vector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

public class QdrantVectorStoreClient implements VectorStoreClient {

    private final VectorStoreProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Set<Integer> ensuredDimensions = ConcurrentHashMap.newKeySet();

    public QdrantVectorStoreClient(VectorStoreProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
    }

    @Override
    public boolean configured() {
        return properties.qdrantConfigured();
    }

    @Override
    public void upsertChunks(long userId, LearningMaterialEntity material, List<MaterialChunkEntity> chunks, Map<Long, List<Double>> embeddingsByChunkId) {
        if (!configured() || material == null || chunks == null || chunks.isEmpty() || embeddingsByChunkId == null || embeddingsByChunkId.isEmpty()) {
            return;
        }
        try {
            List<Map<String, Object>> points = new ArrayList<>();
            for (MaterialChunkEntity chunk : chunks) {
                if (chunk.getId() == null) {
                    continue;
                }
                List<Double> vector = embeddingsByChunkId.get(chunk.getId());
                if (vector == null || vector.isEmpty()) {
                    continue;
                }
                ensureCollection(vector.size());
                points.add(Map.of(
                    "id", pointId(userId, material.getId(), chunk.getId()).toString(),
                    "vector", vector,
                    "payload", Map.of(
                        "userId", userId,
                        "materialId", material.getId(),
                        "chunkId", chunk.getId(),
                        "chunkIndex", chunk.getChunkIndex() == null ? -1 : chunk.getChunkIndex(),
                        "title", material.getTitle() == null ? "" : material.getTitle(),
                        "summary", chunk.getSummary() == null ? "" : chunk.getSummary(),
                        "keywords", chunk.getKeywords() == null ? "" : chunk.getKeywords()
                    )
                ));
            }
            if (points.isEmpty()) {
                return;
            }
            Map<String, Object> body = Map.of("points", points);
            sendJson("PUT", collectionUri("/points?wait=true"), body);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void deleteMaterial(long userId, long materialId) {
        if (!configured()) {
            return;
        }
        try {
            Map<String, Object> body = Map.of("filter", filter(userId, materialId));
            sendJson("POST", collectionUri("/points/delete?wait=true"), body);
        } catch (Exception ignored) {
        }
    }

    @Override
    public List<VectorSearchResult> search(long userId, Long materialId, List<Double> queryEmbedding, int limit, double scoreThreshold) {
        if (!configured() || queryEmbedding == null || queryEmbedding.isEmpty() || limit <= 0) {
            return List.of();
        }
        try {
            ensureCollection(queryEmbedding.size());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", queryEmbedding);
            body.put("limit", limit);
            body.put("with_payload", true);
            body.put("score_threshold", scoreThreshold);
            body.put("filter", filter(userId, materialId));
            HttpResponse<String> response = sendJson("POST", collectionUri("/points/search"), body);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            JsonNode result = objectMapper.readTree(response.body()).path("result");
            if (!result.isArray() || result.isEmpty()) {
                return List.of();
            }
            List<VectorSearchResult> results = new ArrayList<>();
            for (JsonNode node : result) {
                JsonNode payload = node.path("payload");
                long resultMaterialId = payload.path("materialId").asLong(-1);
                long chunkId = payload.path("chunkId").asLong(-1);
                double score = node.path("score").asDouble(0.0);
                if (resultMaterialId > 0 && chunkId > 0 && score > 0.0) {
                    results.add(new VectorSearchResult(resultMaterialId, chunkId, score));
                }
            }
            return results;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void ensureCollection(int vectorSize) throws Exception {
        if (vectorSize <= 0 || ensuredDimensions.contains(vectorSize)) {
            return;
        }
        HttpResponse<String> existing = send("GET", collectionUri(""), null);
        if (existing.statusCode() >= 200 && existing.statusCode() < 300) {
            ensuredDimensions.add(vectorSize);
            return;
        }
        Map<String, Object> body = Map.of(
            "vectors", Map.of(
                "size", vectorSize,
                "distance", "Cosine"
            )
        );
        HttpResponse<String> response = send("PUT", collectionUri(""), body);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            ensuredDimensions.add(vectorSize);
        }
    }

    private Map<String, Object> filter(long userId, Long materialId) {
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(match("userId", userId));
        if (materialId != null) {
            must.add(match("materialId", materialId));
        }
        return Map.of("must", must);
    }

    private Map<String, Object> match(String key, Object value) {
        return Map.of(
            "key", key,
            "match", Map.of("value", value)
        );
    }

    private UUID pointId(long userId, long materialId, long chunkId) {
        return UUID.nameUUIDFromBytes(("u:" + userId + ":m:" + materialId + ":c:" + chunkId).getBytes(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> sendJson(String method, URI uri, Object body) throws Exception {
        return send(method, uri, body);
    }

    private HttpResponse<String> send(String method, URI uri, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(properties.timeout())
            .header("Content-Type", "application/json");
        if (!properties.apiKey().isBlank()) {
            builder.header("api-key", properties.apiKey());
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            String json = objectMapper.writeValueAsString(body);
            builder.method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI collectionUri(String suffix) {
        String baseUrl = properties.baseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return URI.create(baseUrl + "/collections/" + properties.collection() + suffix);
    }
}
