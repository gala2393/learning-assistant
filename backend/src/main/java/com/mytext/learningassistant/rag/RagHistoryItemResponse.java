package com.mytext.learningassistant.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RagHistoryItemResponse(
    Long id,
    String title,
    String question,
    String answer,
    String createdAt,
    Long favoriteId,
    boolean favorite,
    boolean pinned
) {
}
