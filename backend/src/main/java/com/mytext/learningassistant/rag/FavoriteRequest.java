package com.mytext.learningassistant.rag;

import jakarta.validation.constraints.NotNull;

public record FavoriteRequest(
    @NotNull(message = "问题ID不能为空")
    Long questionId
) {
}
