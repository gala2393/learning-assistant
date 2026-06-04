package com.mytext.learningassistant.rerank;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 重排序功能的配置属性记录。
 * <p>
 * 对应 application.yml 中以 "app.reranker" 为前缀的配置项。
 * 使用 Java Record 定义，所有字段为只读，构造时会自动进行默认值填充和归一化处理。
 * <p>
 * 配置示例：
 * <pre>
 * app:
 *   reranker:
 *     enabled: true
 *     provider: local          # 或 cohere / openai 等
 *     base-url: https://api.example.com
 *     api-key: sk-xxx
 *     model: bge-reranker-v2-m3
 *     timeout: 10s
 *     candidates: 30
 *     retrieval-weight: 0.55
 *     lexical-weight: 0.45
 * </pre>
 *
 * @param enabled         是否启用重排序功能
 * @param provider        重排序服务提供者（"local" 表示本地启发式，其他值表示外部 API）
 * @param baseUrl         外部重排序 API 的基础 URL
 * @param apiKey          外部重排序 API 的认证密钥
 * @param model           外部重排序模型名称（默认 "bge-reranker-v2-m3"）
 * @param timeout         API 请求超时时间（默认 10 秒）
 * @param candidates      参与重排序的最大候选文档数量（默认 30）
 * @param retrievalWeight 检索得分的权重（本地启发式模式下使用，会自动归一化）
 * @param lexicalWeight   词法匹配得分的权重（本地启发式模式下使用，会自动归一化）
 */
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

    /**
     * 紧凑构造方法（Canonical Constructor）。
     * <p>
     * 在 Record 创建时自动执行，负责：
     * <ul>
     *   <li>为 null 或空白的字符串字段设置默认值</li>
     *   <li>为 null 或非法的数值字段设置默认值</li>
     *   <li>将权重归一化，使检索权重和词法权重之和为 1.0</li>
     * </ul>
     */
    public RerankerProperties {
        // 设置各字段的默认值
        provider = provider == null || provider.isBlank() ? "local" : provider.trim();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null || model.isBlank() ? "bge-reranker-v2-m3" : model.trim();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        candidates = candidates <= 0 ? 30 : candidates;
        retrievalWeight = retrievalWeight <= 0.0 ? 0.55 : retrievalWeight;
        lexicalWeight = lexicalWeight <= 0.0 ? 0.45 : lexicalWeight;

        // 权重归一化：确保 retrievalWeight + lexicalWeight = 1.0
        double total = retrievalWeight + lexicalWeight;
        if (total <= 0.0) {
            // 如果总权重为 0 或负数，使用默认值
            retrievalWeight = 0.55;
            lexicalWeight = 0.45;
        } else {
            // 按比例归一化，使两权重之和为 1.0
            retrievalWeight = retrievalWeight / total;
            lexicalWeight = lexicalWeight / total;
        }
    }

    /**
     * 判断是否配置了外部重排序 API。
     * <p>
     * 需要同时满足以下条件才返回 true：
     * <ul>
     *   <li>重排序功能已启用（enabled = true）</li>
     *   <li>配置了非空的 API 基础地址（baseUrl 不为空）</li>
     *   <li>provider 不是 "local"（即使用外部服务）</li>
     * </ul>
     *
     * @return 如果外部 API 已正确配置则返回 true，否则返回 false
     */
    public boolean externalConfigured() {
        return enabled && !baseUrl.isBlank() && !"local".equalsIgnoreCase(provider);
    }
}
