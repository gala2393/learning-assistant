package com.mytext.learningassistant.vector;

/**
 * 向量搜索结果记录类。
 *
 * <p>封装从向量数据库中检索到的单条匹配结果。</p>
 *
 * <p>各字段含义：</p>
 * <ul>
 *   <li>{@code materialId} - 匹配文本块所属的学习资料 ID</li>
 *   <li>{@code chunkId}    - 匹配的文本块 ID，可用于回溯获取原始文本</li>
 *   <li>{@code score}      - 相似度分数，范围通常为 0~1（值越大表示越相似）</li>
 * </ul>
 */
public record VectorSearchResult(
    /** 匹配文本块所属的学习资料 ID */
    long materialId,
    /** 匹配的文本块 ID */
    long chunkId,
    /** 与查询向量的相似度分数（余弦相似度，0~1） */
    double score
) {
}
