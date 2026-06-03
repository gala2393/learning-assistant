package com.mytext.learningassistant.rerank;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RerankerProperties.class)
public class RerankerConfiguration {

    @Bean
    public RerankerClient rerankerClient(RerankerProperties properties, ObjectMapper objectMapper) {
        RerankerClient local = new LocalHeuristicRerankerClient(properties);
        if (properties.externalConfigured()) {
            return new ExternalApiRerankerClient(properties, objectMapper, local);
        }
        return local;
    }
}
