package com.mytext.learningassistant.vector;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(VectorStoreProperties.class)
public class VectorStoreConfiguration {

    @Bean
    public VectorStoreClient vectorStoreClient(VectorStoreProperties properties, ObjectMapper objectMapper) {
        return new QdrantVectorStoreClient(properties, objectMapper);
    }
}
