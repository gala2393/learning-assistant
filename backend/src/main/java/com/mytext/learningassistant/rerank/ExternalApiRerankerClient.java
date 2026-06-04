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

/**
 * 基于外部 API 的重排序客户端实现。
 * <p>
 * 该类通过 HTTP 请求调用外部重排序服务（例如 Cohere Rerank、BGE Reranker 等），
 * 对检索阶段返回的候选文档进行二次排序，从而提升最终结果的相关性。
 * <p>
 * 当外部服务不可用或未配置时，会自动降级到 {@link RerankerClient} 的回退实现
 * （通常是 {@link LocalHeuristicRerankerClient}）。
 */
public class ExternalApiRerankerClient implements RerankerClient {

    /** 重排序配置属性，包含 API 地址、密钥、模型名称、超时时间等 */
    private final RerankerProperties properties;

    /** Jackson 的 JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** 回退客户端，当外部 API 调用失败或未配置时使用 */
    private final RerankerClient fallback;

    /** 用于发送 HTTP 请求的客户端 */
    private final HttpClient httpClient;

    /**
     * 构造方法。
     *
     * @param properties   重排序配置属性
     * @param objectMapper JSON 序列化工具
     * @param fallback     回退用的重排序客户端（通常是本地启发式实现）
     */
    public ExternalApiRerankerClient(
        RerankerProperties properties,
        ObjectMapper objectMapper,
        RerankerClient fallback
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        // 构建 HTTP 客户端，并设置连接超时时间
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
    }

    /**
     * 对候选文档列表进行重排序。
     * <p>
     * 执行逻辑：
     * <ol>
     *   <li>如果重排序功能未启用、查询为空、候选数不足 2 个，则直接返回空列表</li>
     *   <li>将候选数限制到配置的最大值</li>
     *   <li>如果外部 API 未配置，则直接使用回退客户端</li>
     *   <li>尝试调用外部重排序 API；若成功则返回结果，否则降级到回退客户端</li>
     * </ol>
     *
     * @param query      用户查询文本
     * @param candidates 待重排序的候选文档列表
     * @return 重排序后的候选列表，按分数从高到低排列
     */
    @Override
    public List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates) {
        // 参数校验：功能未启用、查询为空、候选数不足时返回空列表
        if (!properties.enabled() || query == null || query.isBlank() || candidates == null || candidates.size() < 2) {
            return List.of();
        }
        // 限制候选数不超过配置的最大值
        List<RerankCandidate> limited = candidates.stream()
            .limit(properties.candidates())
            .toList();
        if (limited.size() < 2) {
            return List.of();
        }
        // 如果外部 API 未配置，使用回退实现
        if (!properties.externalConfigured()) {
            return fallback.rerank(query, limited);
        }
        try {
            // 尝试调用外部重排序 API
            List<RerankedCandidate> reranked = callExternalReranker(query, limited);
            // 如果外部 API 返回空结果，则降级到回退实现
            return reranked.isEmpty() ? fallback.rerank(query, limited) : reranked;
        } catch (Exception ignored) {
            // 外部 API 调用出现异常，降级到回退实现
            return fallback.rerank(query, limited);
        }
    }

    /**
     * 调用外部重排序 API 进行文档重排序。
     * <p>
     * 向外部服务发送 POST 请求，请求体包含模型名称、查询文本、候选文档列表等信息。
     * 解析返回的 JSON 响应，提取每个文档的相关性分数，然后按分数降序排列返回。
     *
     * @param query      用户查询文本
     * @param candidates 待重排序的候选文档列表
     * @return 重排序后的候选列表，按分数从高到低排列
     * @throws Exception 网络请求或 JSON 解析异常
     */
    private List<RerankedCandidate> callExternalReranker(String query, List<RerankCandidate> candidates) throws Exception {
        // 构建请求体：包含模型名、查询文本、文档文本列表等
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("query", query);
        body.put("documents", candidates.stream().map(RerankCandidate::text).toList());
        body.put("top_n", candidates.size());
        body.put("return_documents", false);

        // 构建 HTTP 请求
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(normalizedBaseUrl() + "/rerank"))
            .timeout(properties.timeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
        // 如果配置了 API 密钥，则添加 Authorization 请求头
        if (!properties.apiKey().isBlank()) {
            requestBuilder.header("Authorization", "Bearer " + properties.apiKey());
        }

        // 发送请求并获取响应
        HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        // 非 2xx 状态码视为请求失败，返回空列表
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return List.of();
        }

        // 解析 JSON 响应体
        JsonNode root = objectMapper.readTree(response.body());
        // 兼容不同的响应格式：先尝试 "results" 字段，再尝试 "data" 字段
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            results = root.path("data");
        }
        if (!results.isArray() || results.isEmpty()) {
            return List.of();
        }

        // 遍历结果，提取文档索引和相关性分数
        List<RerankedCandidate> reranked = new ArrayList<>();
        for (JsonNode result : results) {
            // 获取文档索引（兼容 "index" 和 "document_index" 两种字段名）
            int index = result.path("index").asInt(-1);
            if (index < 0 && result.has("document_index")) {
                index = result.path("document_index").asInt(-1);
            }
            // 获取相关性分数（兼容 "relevance_score" 和 "score" 两种字段名）
            double score = result.path("relevance_score").asDouble(Double.NaN);
            if (Double.isNaN(score)) {
                score = result.path("score").asDouble(Double.NaN);
            }
            // 索引越界或分数无效时跳过该条结果
            if (index < 0 || index >= candidates.size() || Double.isNaN(score)) {
                continue;
            }
            // 根据索引找到对应的候选文档，包装为重排序结果
            reranked.add(new RerankedCandidate(candidates.get(index).id(), score));
        }
        // 按分数从高到低排序后返回
        return reranked.stream()
            .sorted(Comparator.comparingDouble(RerankedCandidate::score).reversed())
            .toList();
    }

    /**
     * 规范化 API 基础地址，去除末尾的斜杠。
     * <p>
     * 例如将 "https://api.example.com/" 转换为 "https://api.example.com"，
     * 以避免拼接路径时出现双斜杠问题。
     *
     * @return 去除末尾斜杠后的基础地址
     */
    private String normalizedBaseUrl() {
        String baseUrl = properties.baseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}
