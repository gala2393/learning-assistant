package com.mytext.learningassistant.rerank;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ExternalApiRerankerClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void reranksUsingExternalApiScores() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rerank", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            respond(exchange, 200, """
                {
                  "results": [
                    {"index": 1, "relevance_score": 0.94},
                    {"index": 0, "relevance_score": 0.21}
                  ]
                }
                """);
        });
        server.start();

        ExternalApiRerankerClient reranker = new ExternalApiRerankerClient(
            properties("http://127.0.0.1:" + server.getAddress().getPort()),
            new ObjectMapper(),
            (query, candidates) -> List.of()
        );

        List<RerankedCandidate> reranked = reranker.rerank(
            "database index speed",
            List.of(
                new RerankCandidate(1, "unrelated retrieval text", 0.95),
                new RerankCandidate(2, "database indexes speed query lookup", 0.30)
            )
        );

        assertThat(reranked).extracting(RerankedCandidate::id).containsExactly(2L, 1L);
        assertThat(requestBody.get()).contains("\"query\":\"database index speed\"");
        assertThat(requestBody.get()).contains("\"documents\"");
        assertThat(requestBody.get()).contains("\"model\":\"bge-reranker-v2-m3\"");
    }

    @Test
    void fallsBackToLocalRerankerWhenExternalApiFails() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rerank", exchange -> respond(exchange, 500, "{}"));
        server.start();
        LocalHeuristicRerankerClient local = new LocalHeuristicRerankerClient(
            new RerankerProperties(true, "local", "", "", "", Duration.ofSeconds(2), 30, 0.1, 0.9)
        );
        ExternalApiRerankerClient reranker = new ExternalApiRerankerClient(
            properties("http://127.0.0.1:" + server.getAddress().getPort()),
            new ObjectMapper(),
            local
        );

        List<RerankedCandidate> reranked = reranker.rerank(
            "database index speed",
            new ArrayList<>(List.of(
                new RerankCandidate(1, "unrelated cache material", 0.95),
                new RerankCandidate(2, "database index speed query lookup", 0.30)
            ))
        );

        assertThat(reranked).isNotEmpty();
        assertThat(reranked.getFirst().id()).isEqualTo(2L);
    }

    private RerankerProperties properties(String baseUrl) {
        return new RerankerProperties(
            true,
            "api",
            baseUrl,
            "secret",
            "bge-reranker-v2-m3",
            Duration.ofSeconds(2),
            30,
            0.55,
            0.45
        );
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
