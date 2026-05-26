package com.mytext.learningassistant.embedding;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
public class EmbeddingConfiguration {

    @Bean
    public EmbeddingClient embeddingClient(EmbeddingProperties properties) {
        return new OpenAiCompatibleEmbeddingClient(properties);
    }
}
