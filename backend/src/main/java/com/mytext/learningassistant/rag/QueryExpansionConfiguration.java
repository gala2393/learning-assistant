package com.mytext.learningassistant.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(QueryExpansionProperties.class)
public class QueryExpansionConfiguration {
}
