package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * RAG 评估套件响应记录 —— 表示一次评估套件运行的整体结果。
 *
 * @param totalCases                     总用例数
 * @param passedCases                    通过的用例数
 * @param passRate                       通过率（0~1）
 * @param averageFaithfulnessScore       平均忠实度得分
 * @param averageContextRelevanceScore   平均上下文相关性得分
 * @param averageOverallScore            平均综合得分
 * @param cases                          各用例的详细评估结果
 */
public record RagEvaluationSuiteResponse(
    int totalCases,
    int passedCases,
    double passRate,
    double averageFaithfulnessScore,
    double averageContextRelevanceScore,
    double averageOverallScore,
    List<RagEvaluationCaseResponse> cases
) {
}
