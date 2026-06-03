package com.mytext.learningassistant.rerank;

public record RerankCandidate(
    long id,
    String text,
    double retrievalScore
) {
}
