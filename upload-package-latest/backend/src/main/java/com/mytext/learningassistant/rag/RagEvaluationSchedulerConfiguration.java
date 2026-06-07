package com.mytext.learningassistant.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * RAG 评估调度器配置类 —— 启用定时任务调度功能，并注册调度器配置属性。
 *
 * <p>本配置类的作用：</p>
 * <ul>
 *   <li>通过 {@code @EnableScheduling} 启用 Spring 的定时任务功能</li>
 *   <li>通过 {@code @EnableConfigurationProperties} 注册 {@link RagEvaluationSchedulerProperties}，
 *       使配置文件中的调度参数生效</li>
 * </ul>
 *
 * <p>实际的定时任务逻辑在 {@link RagEvaluationSuiteScheduler} 中实现。</p>
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RagEvaluationSchedulerProperties.class)
public class RagEvaluationSchedulerConfiguration {
}
