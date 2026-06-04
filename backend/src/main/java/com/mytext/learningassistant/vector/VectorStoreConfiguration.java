package com.mytext.learningassistant.vector;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量存储模块的 Spring 配置类。
 *
 * <p>职责：在 Spring 容器启动时，读取 application.yml 中 {@code app.vector-store.*} 前缀的配置，
 * 并创建 {@link VectorStoreClient} 的 Bean 实例（默认使用 {@link QdrantVectorStoreClient}）。</p>
 */
@Configuration
@EnableConfigurationProperties(VectorStoreProperties.class)
public class VectorStoreConfiguration {

    /**
     * 创建并注册 {@link VectorStoreClient} Bean。
     *
     * @param properties   从配置文件中读取到的向量存储属性
     * @param objectMapper Spring 容器中的 JSON 处理器
     * @return Qdrant 向量存储客户端实例
     */
    @Bean
    public VectorStoreClient vectorStoreClient(VectorStoreProperties properties, ObjectMapper objectMapper) {
        return new QdrantVectorStoreClient(properties, objectMapper);
    }
}
