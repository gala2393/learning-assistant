package com.mytext.learningassistant.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(RagEvaluationSchedulerProperties.class)
public class RagEvaluationSchedulerConfiguration {
}
