package com.mytext.learningassistant.embedding;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 向量嵌入模块的配置属性记录类。
 *
 * <p>自动绑定 application.yml 中 {@code app.embedding.*} 前缀的配置项。</p>
 *
 * <p>各字段说明：</p>
 * <ul>
 *   <li>{@code enabled}   - 是否启用嵌入功能</li>
 *   <li>{@code baseUrl}   - 嵌入服务的基础地址（如 OpenAI 兼容接口地址）</li>
 *   <li>{@code apiKey}    - 调用嵌入服务所需的 API 密钥</li>
 *   <li>{@code model}     - 使用的嵌入模型名称</li>
 *   <li>{@code timeout}   - HTTP 请求超时时间</li>
 *   <li>{@code topK}      - 向量检索时返回的最大结果数</li>
 *   <li>{@code scoreThreshold} - 向量检索时的最低相似度阈值，低于此分数的结果会被过滤</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(
    boolean enabled,
    String baseUrl,
    String apiKey,
    String model,
    Duration timeout,
    int topK,
    double scoreThreshold
) {

    /**
     * 紧凑构造器：对各字段做归一化和默认值处理。
     * <ul>
     *   <li>字符串字段：null 转为空串，去除首尾空白</li>
     *   <li>timeout：null 时默认 20 秒</li>
     *   <li>topK：小于等于 0 时默认 5</li>
     *   <li>scoreThreshold：小于等于 0 时默认 0.55</li>
     * </ul>
     */
    public EmbeddingProperties {
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
        topK = topK <= 0 ? 5 : topK;
        scoreThreshold = scoreThreshold <= 0.0 ? 0.55 : scoreThreshold;
    }

    /**
     * 判断嵌入功能是否已正确配置（启用 + 必填字段不为空）。
     *
     * @return 如果 {@code enabled} 为 true 且 baseUrl、apiKey、model 均非空白，返回 true
     */
    public boolean configured() {
        return enabled
            && !baseUrl.isBlank()
            && !apiKey.isBlank()
            && !model.isBlank();
    }
}
