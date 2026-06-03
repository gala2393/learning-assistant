package com.mytext.learningassistant.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record RagEvaluationSuiteScheduleRequest(
    boolean scheduled,
    @Min(1)
    @Max(24 * 30)
    Integer intervalHours
) {
}
