package com.mytext.learningassistant.rag;

import java.util.List;

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
