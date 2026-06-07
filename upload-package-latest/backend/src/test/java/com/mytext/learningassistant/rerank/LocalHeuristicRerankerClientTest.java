package com.mytext.learningassistant.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class LocalHeuristicRerankerClientTest {

    @Test
    void rerankBoostsCandidateWithSpecificQueryTerms() {
        LocalHeuristicRerankerClient reranker = new LocalHeuristicRerankerClient(
            new RerankerProperties(true, "local", "", "", "", Duration.ofSeconds(10), 30, 0.4, 0.6)
        );

        List<RerankedCandidate> reranked = reranker.rerank(
            "TCP协议的优缺点",
            List.of(
                new RerankCandidate(1, "UDP协议的优点是延迟低，缺点是不保证可靠传输。", 0.95),
                new RerankCandidate(2, "TCP协议的优点是可靠传输、有序到达，缺点是握手和重传带来额外开销。", 0.60)
            )
        );

        assertThat(reranked).isNotEmpty();
        assertThat(reranked.getFirst().id()).isEqualTo(2);
    }

    @Test
    void disabledRerankerReturnsEmptyResultSoCallerCanKeepRetrievalOrder() {
        LocalHeuristicRerankerClient reranker = new LocalHeuristicRerankerClient(
            new RerankerProperties(false, "local", "", "", "", Duration.ofSeconds(10), 30, 0.55, 0.45)
        );

        assertThat(reranker.rerank(
            "TCP协议",
            List.of(
                new RerankCandidate(1, "TCP协议内容", 0.9),
                new RerankCandidate(2, "UDP协议内容", 0.8)
            )
        )).isEmpty();
    }
}
