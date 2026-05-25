package com.mytext.learningassistant.rag;

import jakarta.validation.constraints.NotBlank;

public record RenameHistoryRequest(
    @NotBlank String title
) {
}
