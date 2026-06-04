package com.mytext.learningassistant.rag;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 查询扩展（Query Expansion）配置类 —— 启用并注册 {@link QueryExpansionProperties} 配置属性。
 *
 * <p>查询扩展是 RAG 检索阶段的一种优化策略。当用户的原始问题过于简短或模糊时，
 * 系统会利用 LLM 生成多个语义相近的查询变体，从而提高检索的召回率。</p>
 *
 * <p>具体配置项（通过 {@code app.query-expansion.*} 前缀在 application.yml 中配置）：</p>
 * <ul>
 *   <li>是否启用查询扩展</li>
 *   <li>最大扩展查询数量</li>
 *   <li>是否启用 HyDE（假设性文档嵌入）策略</li>
 *   <li>HyDE 权重</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(QueryExpansionProperties.class)
public class QueryExpansionConfiguration {
}
