package com.mytext.learningassistant.rag;

import java.util.List;

public record RagStreamResult(
    Long questionId,
    Long conversationId,
    String answer,
    List<RagSourceResponse> sources
) {
}
