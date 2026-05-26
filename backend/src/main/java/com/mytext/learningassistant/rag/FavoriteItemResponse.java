package com.mytext.learningassistant.rag;

import java.util.List;

public record FavoriteItemResponse(
    Long id,
    Long questionId,
    Long conversationId,
    String question,
    String answer,
    String createdAt,
    List<RagHistoryMessageResponse> messages
) {
}
