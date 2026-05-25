package com.mytext.learningassistant.rag;

public record FavoriteItemResponse(
    Long id,
    Long questionId,
    String question,
    String answer,
    String createdAt
) {
}
