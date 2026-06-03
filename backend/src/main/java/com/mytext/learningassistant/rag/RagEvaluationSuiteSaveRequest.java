package com.mytext.learningassistant.rag;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record RagEvaluationSuiteSaveRequest(
    @NotBlank
    @Size(max = 120)
    String name,
    @Size(max = 1000)
    String description,
    @Valid
    @NotEmpty
    @Size(max = 25)
    List<RagEvaluationCaseRequest> cases
) {
}
