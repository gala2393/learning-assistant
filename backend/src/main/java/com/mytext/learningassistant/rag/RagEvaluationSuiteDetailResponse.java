package com.mytext.learningassistant.rag;

import java.util.List;

public record RagEvaluationSuiteDetailResponse(
    Long id,
    String name,
    String description,
    List<RagEvaluationCaseRequest> cases,
    RagEvaluationSuiteRunResponse latestRun,
    boolean scheduled,
    int scheduleIntervalHours,
    String nextRunAt,
    String createdAt,
    String updatedAt
) {
}
