package com.mytext.learningassistant.rag;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagEvaluationCaseRequest(
    @NotBlank
    @Size(max = 2000)
    String question,
    Long materialId,
    List<String> expectedAnswerTerms,
    List<String> expectedSourceTerms
) {
}
