package com.mytext.learningassistant.rerank;

/**
 * 重排序的输入候选记录。
 * <p>
 * 表示从检索阶段获取的单个候选文档，包含文档 ID、文本内容以及检索阶段的原始得分。
 * 该记录作为重排序（Rerank）流程的输入，由 {@link RerankerClient} 的实现类进行处理。
 *
 * @param id             文档的唯一标识符
 * @param text           文档的文本内容，将被发送给重排序模型进行相关性评估
 * @param retrievalScore 检索阶段的原始得分（例如向量相似度得分），用于本地启发式重排序中的权重计算
 */
public record RerankCandidate(
    /** 文档唯一标识符 */
    long id,
    /** 文档文本内容 */
    String text,
    /** 检索阶段的原始得分 */
    double retrievalScore
) {
}
