package com.mytext.learningassistant.rerank;

/**
 * 重排序后的候选记录。
 * <p>
 * 表示经过重排序处理后的单个候选结果，包含文档 ID 和重排序得分。
 * 该记录由 {@link RerankerClient} 的实现类返回，调用方可根据 {@code score}
 * 对结果进行降序排列，从而获得与查询最相关的文档。
 *
 * @param id    文档的唯一标识符，与 {@link RerankCandidate#id()} 对应
 * @param score 重排序后的相关性得分，值越大表示与查询越相关
 */
public record RerankedCandidate(
    /** 文档唯一标识符 */
    long id,
    /** 重排序后的相关性得分 */
    double score
) {
}
