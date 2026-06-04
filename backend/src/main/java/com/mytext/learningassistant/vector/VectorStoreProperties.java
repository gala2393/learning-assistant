package com.mytext.learningassistant.vector;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量存储模块的配置属性记录类。
 *
 * <p>自动绑定 application.yml 中 {@code app.vector-store.*} 前缀的配置项。</p>
 *
 * <p>各字段说明：</p>
 * <ul>
 *   <li>{@code enabled}    - 是否启用向量存储功能</li>
 *   <li>{@code provider}   - 向量数据库提供商（默认 "qdrant"）</li>
 *   <li>{@code baseUrl}    - 向量数据库的 REST API 地址</li>
 *   <li>{@code apiKey}     - 访问向量数据库所需的 API 密钥（可选）</li>
 *   <li>{@code collection} - Qdrant 中的集合名称（默认 "learning_assistant_chunks"）</li>
 *   <li>{@code timeout}    - HTTP 请求超时时间</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.vector-store")
public record VectorStoreProperties(
    boolean enabled,
    String provider,
    String baseUrl,
    String apiKey,
    String collection,
    Duration timeout
) {

    /**
     * 紧凑构造器：对各字段做归一化和默认值处理。
     * <ul>
     *   <li>provider：null 或空白时默认 "qdrant"，转小写</li>
     *   <li>baseUrl：null 转为空串，去除首尾空白</li>
     *   <li>apiKey：null 转为空串，去除首尾空白</li>
     *   <li>collection：null 或空白时默认 "learning_assistant_chunks"</li>
     *   <li>timeout：null 时默认 10 秒</li>
     * </ul>
     */
    public VectorStoreProperties {
        provider = provider == null || provider.isBlank() ? "qdrant" : provider.trim().toLowerCase();
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        collection = collection == null || collection.isBlank() ? "learning_assistant_chunks" : collection.trim();
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
    }

    /**
     * 判断 Qdrant 向量存储是否已正确配置。
     *
     * <p>必须同时满足以下条件：</p>
     * <ul>
     *   <li>{@code enabled} 为 true</li>
     *   <li>{@code provider} 为 "qdrant"</li>
     *   <li>{@code baseUrl} 非空白</li>
     * </ul>
     *
     * @return 满足所有条件时返回 true
     */
    public boolean qdrantConfigured() {
        return enabled && "qdrant".equals(provider) && !baseUrl.isBlank();
    }
}
