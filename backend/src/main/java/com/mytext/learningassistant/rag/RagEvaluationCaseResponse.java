package com.mytext.learningassistant.rag;

import java.util.List;

public record RagEvaluationCaseResponse(
    int caseIndex,
    Long questionId,
    String question,
    double faithfulnessScore,
    double contextRelevanceScore,
    double overallScore,
    double expectedAnswerCoverage,
    double expectedSourceCoverage,
    String verdict,
    boolean passed,
    List<String> missingAnswerTerms,
    List<String> missingSourceTerms
) {
}
