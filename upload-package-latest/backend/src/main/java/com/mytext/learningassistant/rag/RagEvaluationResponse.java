package com.mytext.learningassistant.rag;

/**
 * RAG 评估响应记录 —— 表示单条问答的评估结果。
 *
 * @param id                     评估记录 ID
 * @param questionId             关联的问答 ID
 * @param faithfulnessScore      忠实度得分（0~1）
 * @param contextRelevanceScore  上下文相关性得分（0~1）
 * @param overallScore           综合得分（0~1）
 * @param verdict                判定结果："PASS"、"WARN"、"FAIL"
 * @param evidence               评估证据（关键词匹配情况）
 * @param updatedAt              最后更新时间
 */
public record RagEvaluationResponse(
    Long id,
    Long questionId,
    double faithfulnessScore,
    double contextRelevanceScore,
    double overallScore,
    String verdict,
    String evidence,
    String updatedAt
) {
}
