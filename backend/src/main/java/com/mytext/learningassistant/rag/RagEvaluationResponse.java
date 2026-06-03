package com.mytext.learningassistant.rag;

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
