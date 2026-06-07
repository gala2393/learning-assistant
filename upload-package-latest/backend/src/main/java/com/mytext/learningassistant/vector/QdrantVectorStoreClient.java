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

/**
 * 基于 Qdrant 向量数据库的存储客户端实现。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>管理 Qdrant 中的集合（Collection），自动按需创建</li>
 *   <li>将学习资料的文本块（Chunk）及其嵌入向量写入 Qdrant</li>
 *   <li>按用户/资料维度进行向量相似度搜索</li>
 *   <li>按用户+资料维度删除已存储的向量</li>
 * </ul>
 *
 * <p>Qdrant 点（Point）的 payload 中保存了 userId、materialId、chunkId、
 * chunkIndex、title、summary、keywords 等元数据，用于检索结果的回溯和过滤。</p>
 */
public class QdrantVectorStoreClient implements VectorStoreClient {

    /** 向量存储配置属性（Qdrant 地址、集合名、超时等） */
    private final VectorStoreProperties properties;

    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端，用于与 Qdrant REST API 通信 */
    private final HttpClient httpClient;

    /**
     * 已确认存在的向量维度集合（线程安全）。
     * 避免每次都检查/创建集合，提升性能。
     */
    private final Set<Integer> ensuredDimensions = ConcurrentHashMap.newKeySet();

    /**
     * 构造 Qdrant 向量存储客户端。
     *
     * @param properties   向量存储配置
     * @param objectMapper JSON 处理器
     */
    public QdrantVectorStoreClient(VectorStoreProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
    }

    /**
     * 检查 Qdrant 向量存储是否已正确配置。
     *
     * @return 如果配置中 Qdrant 相关参数有效，返回 true
     */
    @Override
    public boolean configured() {
        return properties.qdrantConfigured();
    }

    /**
     * 将学习资料的文本块及对应的嵌入向量写入（upsert）Qdrant。
     *
     * <p>每个文本块生成一个 Qdrant Point，包含：</p>
     * <ul>
     *   <li>id：基于 userId + materialId + chunkId 生成的确定性 UUID</li>
     *   <li>vector：该文本块的嵌入向量</li>
     *   <li>payload：元数据（用户ID、资料ID、块索引、标题、摘要、关键词）</li>
     * </ul>
     *
     * @param userId             所属用户 ID
     * @param material           学习资料实体
     * @param chunks             资料的文本块列表
     * @param embeddingsByChunkId 文本块 ID 到嵌入向量的映射
     */
    @Override
    public void upsertChunks(long userId, LearningMaterialEntity material, List<MaterialChunkEntity> chunks, Map<Long, List<Double>> embeddingsByChunkId) {
        // 前置校验：配置或参数无效时直接返回
        if (!configured() || material == null || chunks == null || chunks.isEmpty() || embeddingsByChunkId == null || embeddingsByChunkId.isEmpty()) {
            return;
        }
        try {
            List<Map<String, Object>> points = new ArrayList<>();
            for (MaterialChunkEntity chunk : chunks) {
                // 跳过尚未持久化（无 ID）的文本块
                if (chunk.getId() == null) {
                    continue;
                }
                // 查找该文本块对应的嵌入向量
                List<Double> vector = embeddingsByChunkId.get(chunk.getId());
                if (vector == null || vector.isEmpty()) {
                    continue;
                }
                // 确保 Qdrant 中已创建对应维度的集合
                ensureCollection(vector.size());
                // 构造 Qdrant Point
                points.add(Map.of(
                    "id", pointId(userId, material.getId(), chunk.getId()).toString(),  // 确定性 UUID
                    "vector", vector,                                                   // 嵌入向量
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
            // 批量写入 Qdrant，wait=true 表示等待写入完成
            Map<String, Object> body = Map.of("points", points);
            sendJson("PUT", collectionUri("/points?wait=true"), body);
        } catch (Exception ignored) {
            // 写入失败静默处理，不影响主流程
        }
    }

    /**
     * 删除指定用户下某个学习资料的所有向量。
     *
     * @param userId     用户 ID
     * @param materialId 学习资料 ID
     */
    @Override
    public void deleteMaterial(long userId, long materialId) {
        if (!configured()) {
            return;
        }
        try {
            // 通过过滤器匹配 userId + materialId，删除对应的全部 Point
            Map<String, Object> body = Map.of("filter", filter(userId, materialId));
            sendJson("POST", collectionUri("/points/delete?wait=true"), body);
        } catch (Exception ignored) {
        }
    }

    /**
     * 基于向量相似度搜索与查询文本最相关的文本块。
     *
     * <p>搜索范围限定在指定用户的可选资料下，结果按相似度降序排列，
     * 且只返回分数高于 {@code scoreThreshold} 的结果。</p>
     *
     * @param userId         用户 ID，用于过滤搜索范围
     * @param materialId     可选的资料 ID；为 null 时搜索该用户下所有资料
     * @param queryEmbedding 查询文本的嵌入向量
     * @param limit          最大返回结果数
     * @param scoreThreshold 最低相似度阈值，低于此值的结果被过滤
     * @return 匹配的向量搜索结果列表；无结果或异常时返回空列表
     */
    @Override
    public List<VectorSearchResult> search(long userId, Long materialId, List<Double> queryEmbedding, int limit, double scoreThreshold) {
        if (!configured() || queryEmbedding == null || queryEmbedding.isEmpty() || limit <= 0) {
            return List.of();
        }
        try {
            // 确保集合存在
            ensureCollection(queryEmbedding.size());
            // 构造搜索请求体
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vector", queryEmbedding);     // 查询向量
            body.put("limit", limit);               // 返回数量上限
            body.put("with_payload", true);         // 同时返回 payload 元数据
            body.put("score_threshold", scoreThreshold); // 相似度阈值
            body.put("filter", filter(userId, materialId)); // 用户/资料过滤器
            // 发送搜索请求
            HttpResponse<String> response = sendJson("POST", collectionUri("/points/search"), body);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return List.of();
            }
            // 解析搜索结果
            JsonNode result = objectMapper.readTree(response.body()).path("result");
            if (!result.isArray() || result.isEmpty()) {
                return List.of();
            }
            // 遍历每条匹配结果，提取 materialId、chunkId 和 score
            List<VectorSearchResult> results = new ArrayList<>();
            for (JsonNode node : result) {
                JsonNode payload = node.path("payload");
                long resultMaterialId = payload.path("materialId").asLong(-1);
                long chunkId = payload.path("chunkId").asLong(-1);
                double score = node.path("score").asDouble(0.0);
                // 只保留有效数据（ID 为正数且分数大于 0）
                if (resultMaterialId > 0 && chunkId > 0 && score > 0.0) {
                    results.add(new VectorSearchResult(resultMaterialId, chunkId, score));
                }
            }
            return results;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /**
     * 确保 Qdrant 中存在具有指定向量维度的集合。
     *
     * <p>如果该维度已经确认存在，则跳过。否则先尝试查询集合是否已存在，
     * 不存在则使用 Cosine 距离创建新集合。</p>
     *
     * @param vectorSize 向量维度（如 1536、768 等）
     * @throws Exception 网络或序列化异常
     */
    private void ensureCollection(int vectorSize) throws Exception {
        // 已确认过的维度直接跳过
        if (vectorSize <= 0 || ensuredDimensions.contains(vectorSize)) {
            return;
        }
        // 检查集合是否已存在
        HttpResponse<String> existing = send("GET", collectionUri(""), null);
        if (existing.statusCode() >= 200 && existing.statusCode() < 300) {
            ensuredDimensions.add(vectorSize);
            return;
        }
        // 集合不存在，创建新集合（使用 Cosine 余弦距离）
        Map<String, Object> body = Map.of(
            "vectors", Map.of(
                "size", vectorSize,
                "distance", "Cosine"
            )
        );
        HttpResponse<String> response = send("PUT", collectionUri(""), body);
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            ensuredDimensions.add(vectorSize); // 记录已创建，避免重复操作
        }
    }

    /**
     * 构造 Qdrant 过滤条件，按 userId（必选）和 materialId（可选）过滤 Point。
     *
     * @param userId     用户 ID
     * @param materialId 资料 ID（为 null 时不添加该条件）
     * @return Qdrant 格式的 filter Map
     */
    private Map<String, Object> filter(long userId, Long materialId) {
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(match("userId", userId));            // 必须匹配用户 ID
        if (materialId != null) {
            must.add(match("materialId", materialId)); // 可选：匹配资料 ID
        }
        return Map.of("must", must);
    }

    /**
     * 构造 Qdrant 的单个匹配条件。
     *
     * @param key   payload 中的字段名
     * @param value 匹配值
     * @return Qdrant 格式的 match 条件 Map
     */
    private Map<String, Object> match(String key, Object value) {
        return Map.of(
            "key", key,
            "match", Map.of("value", value)
        );
    }

    /**
     * 根据 userId + materialId + chunkId 生成确定性 UUID。
     *
     * <p>使用 UUID v3（基于名称的 UUID），确保相同输入始终生成相同的 UUID。
     * 这样重复上传同一文本块时会覆盖（upsert）而非创建重复 Point。</p>
     *
     * @param userId     用户 ID
     * @param materialId 资料 ID
     * @param chunkId    文本块 ID
     * @return 确定性生成的 UUID
     */
    private UUID pointId(long userId, long materialId, long chunkId) {
        return UUID.nameUUIDFromBytes(("u:" + userId + ":m:" + materialId + ":c:" + chunkId).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 发送 JSON 请求的便捷方法（委托给 {@link #send}）。
     *
     * @param method HTTP 方法（GET、POST、PUT 等）
     * @param uri    请求 URI
     * @param body   请求体对象（会被序列化为 JSON）
     * @return HTTP 响应
     * @throws Exception 网络或序列化异常
     */
    private HttpResponse<String> sendJson(String method, URI uri, Object body) throws Exception {
        return send(method, uri, body);
    }

    /**
     * 发送 HTTP 请求到 Qdrant REST API。
     *
     * <p>自动设置 Content-Type 为 application/json，如果配置了 API 密钥则附加 api-key 请求头。</p>
     *
     * @param method HTTP 方法
     * @param uri    请求 URI
     * @param body   请求体对象（null 表示无请求体）
     * @return HTTP 响应
     * @throws Exception 网络异常
     */
    private HttpResponse<String> send(String method, URI uri, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(properties.timeout())
            .header("Content-Type", "application/json");
        // 如果配置了 Qdrant API 密钥，附加到请求头
        if (!properties.apiKey().isBlank()) {
            builder.header("api-key", properties.apiKey());
        }
        // body 为 null 时发送无体请求，否则序列化为 JSON
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            String json = objectMapper.writeValueAsString(body);
            builder.method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 构造 Qdrant 集合相关的完整请求 URI。
     *
     * <p>自动去除 baseUrl 末尾的斜杠，拼接集合名称和后缀路径。</p>
     * <p>示例：{@code http://localhost:6333} → {@code http://localhost:6333/collections/learning_assistant_chunks/points}</p>
     *
     * @param suffix 路径后缀（如 "/points"、"/points/search" 等）
     * @return 完整的 Qdrant REST API URI
     */
    private URI collectionUri(String suffix) {
        String baseUrl = properties.baseUrl();
        // 去除末尾多余的斜杠
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return URI.create(baseUrl + "/collections/" + properties.collection() + suffix);
    }
}
