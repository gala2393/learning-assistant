package com.mytext.learningassistant.embedding;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量嵌入模块的 Spring 配置类。
 *
 * <p>职责：在 Spring 容器启动时，读取 application.yml 中 {@code app.embedding.*} 前缀的配置，
 * 并创建 {@link EmbeddingClient} 的 Bean 实例（默认使用 {@link OpenAiCompatibleEmbeddingClient}）。</p>
 */
@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {

    /**
     * 创建并注册 {@link EmbeddingClient} Bean。
     *
     * @param properties 从配置文件中读取到的嵌入相关属性
     * @return OpenAI 兼容格式的嵌入客户端实例
     */
    @Bean
    public EmbeddingClient embeddingClient(EmbeddingProperties properties) {
        return new OpenAiCompatibleEmbeddingClient(properties);
    }
}
