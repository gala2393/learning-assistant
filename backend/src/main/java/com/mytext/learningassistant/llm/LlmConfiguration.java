package com.mytext.learningassistant.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {

    @Bean
    public LlmClient llmClient(LlmProperties properties) {
        return new OpenAiCompatibleLlmClient(properties);
    }
}
