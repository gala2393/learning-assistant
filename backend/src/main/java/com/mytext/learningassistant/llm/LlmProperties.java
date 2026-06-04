package com.mytext.learningassistant.llm;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LLM 配置属性记录类
 *
 * 用于绑定 application.yml 中 app.llm 前缀的配置项。
 * 包含 LLM 服务的连接信息、模型选择和超时设置。
 *
 * 使用 @ConfigurationProperties 注解实现类型安全的配置绑定。
 * 使用 Java record 特性，自动生成 getter、equals、hashCode 和 toString 方法。
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
    /** 是否启用 LLM 功能 */
    boolean enabled,
    /** LLM API 的基础 URL 地址 */
    String baseUrl,
    /** LLM API 的访问密钥 */
    String apiKey,
    /** 要使用的模型名称，如 "gpt-4"、"deepseek-chat" 等 */
    String model,
    /** API 格式，支持 "chat-completions"（默认）和 "responses" 两种格式 */
    String apiFormat,
    /** API 调用的超时时间，默认 15 秒 */
    Duration timeout
) {

    /**
     * 紧凑构造函数，用于标准化配置值
     *
     * 对输入的配置值进行规范化处理：
     * - 将 null 值转为空字符串
     * - 去除首尾空格
     * - 设置默认值（apiFormat 默认为 "chat-completions"，timeout 默认为 15 秒）
     */
    public LlmProperties {
        // 标准化各个配置项，将 null 转为空字符串并去除空格
        baseUrl = baseUrl == null ? "" : baseUrl.trim();
        apiKey = apiKey == null ? "" : apiKey.trim();
        model = model == null ? "" : model.trim();
        // API 格式默认使用 chat-completions
        apiFormat = apiFormat == null || apiFormat.isBlank() ? "chat-completions" : apiFormat.trim();
        // 超时时间默认 15 秒
        timeout = timeout == null ? Duration.ofSeconds(15) : timeout;
    }

    /**
     * 检查 LLM 是否已完整配置
     *
     * @return 如果启用且 baseUrl、apiKey、model 都已配置则返回 true，否则返回 false
     */
    public boolean configured() {
        return enabled
            && !baseUrl.isBlank()
            && !apiKey.isBlank()
            && !model.isBlank();
    }

    /**
     * 检查是否使用 Responses API 格式
     *
     * @return 如果 apiFormat 为 "responses"（不区分大小写）则返回 true，否则返回 false
     */
    public boolean responsesApi() {
        return "responses".equalsIgnoreCase(apiFormat);
    }
}
