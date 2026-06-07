package com.mytext.learningassistant.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 模块配置类
 *
 * 负责配置和创建 LLM 客户端的 Spring Bean。
 * 使用 @EnableConfigurationProperties 注解启用 LlmProperties 配置属性绑定。
 */
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {

    /**
     * 创建并注册 LLM 客户端 Bean
     *
     * @param properties LLM 配置属性对象，包含 API 地址、密钥、模型等配置
     * @return LlmClient 接口的实现类实例（OpenAiCompatibleLlmClient）
     */
    @Bean
    public LlmClient llmClient(LlmProperties properties) {
        // 创建兼容 OpenAI 接口的 LLM 客户端实例
        return new OpenAiCompatibleLlmClient(properties);
    }
}
