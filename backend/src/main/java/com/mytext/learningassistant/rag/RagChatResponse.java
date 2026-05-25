package com.mytext.learningassistant.rag;

import java.util.List;

public record RagChatResponse(
    Long questionId,
    String question,
    String answer,
    List<RagSourceResponse> sources,
    String createdAt
) {
}
