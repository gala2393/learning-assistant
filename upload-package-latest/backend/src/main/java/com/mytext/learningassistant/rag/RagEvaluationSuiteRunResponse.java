package com.mytext.learningassistant.rag;

/**
 * 评估套件运行记录响应 —— 表示一次套件运行的结果概要。
 *
 * @param id                         运行记录 ID
 * @param suiteId                    所属套件 ID
 * @param totalCases                 总用例数
 * @param passedCases                通过用例数
 * @param passRate                   通过率
 * @param averageFaithfulnessScore   平均忠实度得分
 * @param averageContextRelevanceScore 平均上下文相关性得分
 * @param averageOverallScore        平均综合得分
 * @param result                     完整的评估结果详情（包含各用例数据）
 * @param createdAt                  运行时间
 */
public record RagEvaluationSuiteRunResponse(
    Long id,
    Long suiteId,
    int totalCases,
    int passedCases,
    double passRate,
    double averageFaithfulnessScore,
    double averageContextRelevanceScore,
    double averageOverallScore,
    RagEvaluationSuiteResponse result,
    String createdAt
) {
}
