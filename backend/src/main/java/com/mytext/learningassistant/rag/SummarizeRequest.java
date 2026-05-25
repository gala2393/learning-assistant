package com.mytext.learningassistant.rag;

import jakarta.validation.constraints.NotNull;

public record SummarizeRequest(
    @NotNull(message = "materialId is required")
    Long materialId
) {
}
