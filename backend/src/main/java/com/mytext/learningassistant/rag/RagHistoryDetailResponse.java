package com.mytext.learningassistant.rag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RagHistoryDetailResponse(
    Long id,
    Long conversationId,
    String title,
    String question,
    String answer,
    String createdAt,
    List<RagHistoryMessageResponse> messages,
    List<RagSourceResponse> sources,
    Long favoriteId,
    boolean favorite,
    boolean pinned
) {
}
