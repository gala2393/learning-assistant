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

/**
 * 基于 OpenAI 兼容接口的嵌入客户端实现。
 *
 * <p>职责：通过 HTTP 调用 OpenAI（或兼容的第三方）Embedding API，
 * 将文本转换为向量表示。</p>
 *
 * <p>工作流程：</p>
 * <ol>
 *   <li>检查配置是否有效</li>
 *   <li>构造 HTTP POST 请求，携带 API 密钥和文本内容</li>
 *   <li>解析响应 JSON，提取第一个嵌入向量</li>
 *   <li>将向量以 {@code Optional<List<Double>>} 形式返回</li>
 * </ol>
 *
 * <p>任何异常或非 2xx 响应都会导致返回 {@code Optional.empty()}，
 * 不会向调用方抛出异常。</p>
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    /** 嵌入相关配置（地址、密钥、模型等） */
    private final EmbeddingProperties properties;

    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端，用于发送请求到嵌入服务 */
    private final HttpClient httpClient;

    /**
     * 使用默认 ObjectMapper 构造客户端。
     *
     * @param properties 嵌入配置属性
     */
    public OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties) {
        this(properties, new ObjectMapper());
    }

    /**
     * 使用自定义 ObjectMapper 构造客户端（便于单元测试注入 Mock）。
     *
     * @param properties   嵌入配置属性
     * @param objectMapper 自定义的 JSON 处理器
     */
    OpenAiCompatibleEmbeddingClient(EmbeddingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 使用配置中的超时时间设置 HTTP 连接超时
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
    }

    /**
     * 将文本转换为嵌入向量。
     *
     * <p>实现步骤：</p>
     * <ol>
     *   <li>前置校验：配置是否有效、文本是否非空</li>
     *   <li>构造 HTTP POST 请求到 embeddings 端点</li>
     *   <li>检查响应状态码是否为 2xx</li>
     *   <li>从响应 JSON 中解析 data[0].embedding 数组</li>
     *   <li>逐个提取浮点值，组装为不可变列表返回</li>
     * </ol>
     *
     * @param text 需要向量化的文本
     * @return 嵌入向量的 Optional 包装；失败或配置缺失时返回 {@code Optional.empty()}
     */
    @Override
    public Optional<List<Double>> embed(String text) {
        // 前置校验：未配置或文本为空则直接返回空
        if (!properties.configured() || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            // Embedding 调用失败不抛到业务层，资料解析会保留 chunk，只是缺少向量索引。
            // 构建 HTTP 请求：设置超时、认证头、JSON 内容类型
            HttpRequest request = HttpRequest.newBuilder(embeddingsUri())
                .timeout(properties.timeout())
                .header("Authorization", "Bearer " + properties.apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(embeddingBody(text)))
                .build();
            // 发送请求并获取响应
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // 非 2xx 响应视为失败
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            // 解析 JSON 响应：提取 data[0].embedding
            JsonNode embeddingNode = objectMapper.readTree(response.body())
                .path("data")
                .path(0)
                .path("embedding");
            if (!embeddingNode.isArray() || embeddingNode.isEmpty()) {
                return Optional.empty();
            }

            // 逐个读取嵌入向量中的浮点数值
            // 严格校验每一维都是数字，避免把错误响应或混合类型数据写入向量库。
            List<Double> embedding = new ArrayList<>(embeddingNode.size());
            for (JsonNode value : embeddingNode) {
                if (!value.isNumber()) {
                    return Optional.empty(); // 遇到非数字元素则中止
                }
                embedding.add(value.asDouble());
            }
            // 返回不可变列表副本
            return embedding.isEmpty() ? Optional.empty() : Optional.of(List.copyOf(embedding));
        } catch (Exception exception) {
            // 网络异常、JSON 解析异常等均静默处理，返回空
            return Optional.empty();
        }
    }

    /**
     * 构造 Embedding API 的完整 URI。
     *
     * <p>自动处理 baseUrl 末尾的斜杠，并确保路径包含 /v1 前缀。
     * 例如：{@code https://api.openai.com} → {@code https://api.openai.com/v1/embeddings}</p>
     *
     * @return Embedding 端点的 URI
     */
    private URI embeddingsUri() {
        String baseUrl = properties.baseUrl();
        // 去除末尾多余的斜杠
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // 如果 baseUrl 已经以 /v1 结尾，直接拼接 /embeddings；否则补上 /v1
        if (baseUrl.endsWith("/v1")) {
            return URI.create(baseUrl + "/embeddings");
        }
        return URI.create(baseUrl + "/v1/embeddings");
    }

    /**
     * 构造 Embedding 请求的 JSON 请求体。
     *
     * @param text 需要嵌入的文本
     * @return 包含 model 和 input 字段的 JSON 字符串
     * @throws Exception JSON 序列化失败时抛出
     */
    private String embeddingBody(String text) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());  // 指定使用的嵌入模型
        body.put("input", text);                // 待嵌入的文本内容
        return objectMapper.writeValueAsString(body);
    }
}
