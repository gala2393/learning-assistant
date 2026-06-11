package com.mytext.learningassistant.material;

/**
 * 资料检索索引状态。
 */
public enum MaterialIndexStatus {
    /** 等待构建索引。 */
    PENDING,
    /** 后台正在构建索引。 */
    RUNNING,
    /** BM25 或部分向量已可用，全文索引仍在补齐。 */
    PARTIAL,
    /** 全部索引构建完成。 */
    READY,
    /** 索引构建失败。 */
    FAILED
}
