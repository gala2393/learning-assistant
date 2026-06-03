package com.mytext.learningassistant.rag;

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
