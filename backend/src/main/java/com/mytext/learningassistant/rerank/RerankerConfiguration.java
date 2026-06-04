package com.mytext.learningassistant.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 重排序功能的 Spring 配置类。
 * <p>
 * 负责根据配置属性自动装配合适的 {@link RerankerClient} Bean：
 * <ul>
 *   <li>如果配置了外部 API（即 {@link RerankerProperties#externalConfigured()} 为 true），
 *       则创建 {@link ExternalApiRerankerClient}，并将本地实现作为回退方案</li>
 *   <li>否则，直接使用 {@link LocalHeuristicRerankerClient} 作为唯一实现</li>
 * </ul>
 * 配置属性通过 {@link RerankerProperties} 从 application.yml 中读取 "app.reranker" 前缀的配置项。
 */
@Configuration
@EnableConfigurationProperties(RerankerProperties.class)
public class RerankerConfiguration {

    /**
     * 创建并注册重排序客户端 Bean。
     * <p>
     * 优先使用外部 API 实现；如果外部 API 未配置，则使用本地启发式实现。
     *
     * @param properties   重排序配置属性
     * @param objectMapper JSON 序列化工具（用于外部 API 请求体的构建和响应解析）
     * @return 重排序客户端实例
     */
    @Bean
    public RerankerClient rerankerClient(RerankerProperties properties, ObjectMapper objectMapper) {
        // 先创建本地启发式实现作为基础（或回退）方案
        RerankerClient local = new LocalHeuristicRerankerClient(properties);
        // 如果配置了外部 API，则包装一层外部 API 客户端
        if (properties.externalConfigured()) {
            return new ExternalApiRerankerClient(properties, objectMapper, local);
        }
        // 否则直接使用本地实现
        return local;
    }
}
