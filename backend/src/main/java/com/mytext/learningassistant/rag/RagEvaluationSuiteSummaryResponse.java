package com.mytext.learningassistant.rag;

public record RagEvaluationSuiteSummaryResponse(
    Long id,
    String name,
    String description,
    int caseCount,
    Integer lastTotalCases,
    Integer lastPassedCases,
    Double lastPassRate,
    Double lastAverageOverallScore,
    String lastRunAt,
    boolean scheduled,
    int scheduleIntervalHours,
    String nextRunAt,
    String updatedAt
) {
}
