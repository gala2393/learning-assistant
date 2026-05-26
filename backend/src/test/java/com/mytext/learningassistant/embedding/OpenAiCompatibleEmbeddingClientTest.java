package com.mytext.learningassistant.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void disabledClientReturnsEmpty() {
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
            new EmbeddingProperties(false, "", "", "text-embedding-v3", Duration.ofSeconds(1), 5, 0.55)
        );

        assertThat(client.embed("hello")).isEmpty();
    }

    @Test
    void sendsEmbeddingRequestAndReadsVector() throws Exception {
        server = startServer(200, """
            {
              "data": [
                { "embedding": [0.1, 0.2, 0.3] }
              ]
            }
            """);
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
            new EmbeddingProperties(true, baseUrl(), "test-key", "text-embedding-v3", Duration.ofSeconds(2), 5, 0.55)
        );

        Optional<List<Double>> result = client.embed("Question?");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow()).containsExactly(0.1, 0.2, 0.3);
    }

    @Test
    void failedProviderCallReturnsEmpty() throws Exception {
        server = startServer(500, """
            { "error": "temporary failure" }
            """);
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(
            new EmbeddingProperties(true, baseUrl(), "test-key", "text-embedding-v3", Duration.ofSeconds(2), 5, 0.55)
        );

        assertThat(client.embed("system")).isEmpty();
    }

    private HttpServer startServer(int status, String body) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/embeddings", exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
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
