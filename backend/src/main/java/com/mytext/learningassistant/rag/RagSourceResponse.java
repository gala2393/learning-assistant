package com.mytext.learningassistant.rag;

public record RagSourceResponse(
    Long materialId,
    Long chunkId,
    String materialTitle,
    Integer pageNo,
    String excerpt,
    Double score
) {
}
