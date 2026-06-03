package com.mytext.learningassistant.rag;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record RagEvaluationSuiteRequest(
    @Valid
    @NotEmpty
    @Size(max = 25)
    List<RagEvaluationCaseRequest> cases
) {
}
