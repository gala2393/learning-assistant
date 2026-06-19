package com.mytext.learningassistant.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 查询扩展（Query Expansion）配置属性 —— 定义 RAG 检索阶段查询扩展的各项参数。
 *
 * <p><strong>什么是查询扩展？</strong></p>
 * <p>查询扩展是一种检索优化技术。当用户问"数据库索引"时，系统可能同时生成
 * "数据库索引的原理"、"索引类型有哪些"等多个查询变体，分别检索后再合并结果，
 * 从而找到更多相关片段。</p>
 *
 * <p><strong>什么是 HyDE？</strong></p>
 * <p>HyDE（Hypothetical Document Embeddings，假设性文档嵌入）是一种检索策略：
 * 先让 LLM 根据问题生成一个"假设性的回答"，再用这个回答的向量去检索，
 * 因为回答的语义通常比问题更接近真正的文档内容。</p>
 *
 * <p>配置前缀：{@code app.query-expansion}</p>
 */
@ConfigurationProperties(prefix = "app.query-expansion")
public class QueryExpansionProperties {

    /** 是否启用查询扩展功能，默认开启 */
    private boolean enabled = true;

    /** 最大扩展查询数量（包括原始查询），默认为 4 */
    private int maxQueries = 4;

    /** 当 LLM 无法生成扩展查询时，是否使用本地规则生成，默认开启 */
    private boolean localFallback = true;

    /** 是否启用 HyDE（假设性文档嵌入）检索策略，默认关闭，避免每次问答额外触发一次 LLM 检索前调用 */
    private boolean hydeEnabled = false;

    /** HyDE 生成的假设性文档在最终混合检索中的权重，默认 0.72 */
    private double hydeWeight = 0.72;

    /**
     * 获取查询扩展是否启用。
     *
     * @return true 表示启用
     */
    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 获取最大扩展查询数量，最小值为 4。
     *
     * @return 最大查询数量
     */
    public int maxQueries() {
        return maxQueries <= 0 ? 4 : maxQueries;
    }

    public void setMaxQueries(int maxQueries) {
        this.maxQueries = maxQueries;
    }

    /**
     * 获取是否启用本地回退策略（当 LLM 不可用时用规则生成扩展查询）。
     *
     * @return true 表示启用本地回退
     */
    public boolean localFallback() {
        return localFallback;
    }

    public void setLocalFallback(boolean localFallback) {
        this.localFallback = localFallback;
    }

    /**
     * 获取是否启用 HyDE 检索策略。
     *
     * @return true 表示启用
     */
    public boolean hydeEnabled() {
        return hydeEnabled;
    }

    public void setHydeEnabled(boolean hydeEnabled) {
        this.hydeEnabled = hydeEnabled;
    }

    /**
     * 获取 HyDE 权重，范围在 (0, 1] 之间，默认 0.72。
     *
     * @return HyDE 检索结果的权重
     */
    public double hydeWeight() {
        if (hydeWeight <= 0.0) {
            return 0.72;
        }
        return Math.min(1.0, hydeWeight);
    }

    public void setHydeWeight(double hydeWeight) {
        this.hydeWeight = hydeWeight;
    }
}
