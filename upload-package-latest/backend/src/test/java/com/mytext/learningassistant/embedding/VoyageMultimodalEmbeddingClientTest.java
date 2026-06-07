package com.mytext.learningassistant.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VoyageMultimodalEmbeddingClientTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<String> requestBodies = new ArrayList<>();
    private final List<String> requestPaths = new ArrayList<>();
    private final List<String> authorizationHeaders = new ArrayList<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void documentEmbeddingUsesVoyageMultimodalPayload() throws Exception {
        server = startServer();
        VoyageMultimodalEmbeddingClient client = new VoyageMultimodalEmbeddingClient(
            new EmbeddingProperties(true, baseUrl(), "test-key", "voyage-multimodal-3", Duration.ofSeconds(2), 8, 0.4)
        );

        Optional<List<Double>> result = client.embedDocument("Study document text");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).containsExactly(0.1, 0.2, 0.3);
        assertThat(requestPaths).containsExactly("/v1/multimodalembeddings");
        assertThat(authorizationHeaders).containsExactly("Bearer test-key");

        JsonNode body = OBJECT_MAPPER.readTree(requestBodies.get(0));
        assertThat(body.path("model").asText()).isEqualTo("voyage-multimodal-3");
        assertThat(body.path("input_type").asText()).isEqualTo("document");
        assertThat(body.path("truncation").asBoolean()).isTrue();
        assertThat(body.path("inputs").path(0).path("content").path(0).path("type").asText()).isEqualTo("text");
        assertThat(body.path("inputs").path(0).path("content").path(0).path("text").asText()).isEqualTo("Study document text");
    }

    @Test
    void queryEmbeddingUsesQueryInputType() throws Exception {
        server = startServer();
        VoyageMultimodalEmbeddingClient client = new VoyageMultimodalEmbeddingClient(
            new EmbeddingProperties(true, baseUrl(), "test-key", "voyage-multimodal-3", Duration.ofSeconds(2), 8, 0.4)
        );

        Optional<List<Double>> result = client.embedQuery("What is RAG?");

        assertThat(result).isPresent();
        JsonNode body = OBJECT_MAPPER.readTree(requestBodies.get(0));
        assertThat(body.path("input_type").asText()).isEqualTo("query");
        assertThat(body.path("inputs").path(0).path("content").path(0).path("text").asText()).isEqualTo("What is RAG?");
    }

    private HttpServer startServer() throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/multimodalembeddings", exchange -> {
            requestPaths.add(exchange.getRequestURI().getPath());
            authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = """
                {
                  "object": "list",
                  "data": [
                    { "object": "embedding", "embedding": [0.1, 0.2, 0.3], "index": 0 }
                  ],
                  "model": "voyage-multimodal-3"
                }
                """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
