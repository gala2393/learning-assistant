package com.mytext.learningassistant.rerank;

import java.util.List;

public interface RerankerClient {

    List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates);
}
