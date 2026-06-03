package com.mytext.learningassistant.vector;

public record VectorSearchResult(
    long materialId,
    long chunkId,
    double score
) {
}
