package com.mytext.learningassistant.rag;

import java.util.List;

public record RagStreamResult(
    Long questionId,
    String answer,
    List<RagSourceResponse> sources
) {
}
