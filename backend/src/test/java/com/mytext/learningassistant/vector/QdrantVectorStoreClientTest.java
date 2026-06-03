package com.mytext.learningassistant.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class QdrantVectorStoreClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void upsertAndSearchUseQdrantPayloadFilters() throws Exception {
        List<String> requests = new ArrayList<>();
        AtomicBoolean collectionCreated = new AtomicBoolean(false);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            String body = new String(exchange.getRequestBody().readAllBytes());
            requests.add(method + " " + path + " " + body);
            if ("GET".equals(method) && path.equals("/collections/test_chunks")) {
                respond(exchange, collectionCreated.get() ? 200 : 404, "{}");
                return;
            }
            if ("PUT".equals(method) && path.equals("/collections/test_chunks")) {
                collectionCreated.set(true);
                respond(exchange, 200, "{\"result\":true}");
                return;
            }
            if ("PUT".equals(method) && path.equals("/collections/test_chunks/points")) {
                respond(exchange, 200, "{\"result\":true}");
                return;
            }
            if ("POST".equals(method) && path.equals("/collections/test_chunks/points/search")) {
                respond(exchange, 200, """
                    {"result":[{"score":0.91,"payload":{"userId":7,"materialId":11,"chunkId":13}}]}
                    """);
                return;
            }
            respond(exchange, 404, "{}");
        });
        server.start();

        QdrantVectorStoreClient client = new QdrantVectorStoreClient(
            new VectorStoreProperties(true, "qdrant", "http://127.0.0.1:" + server.getAddress().getPort(), "", "test_chunks", Duration.ofSeconds(2)),
            new ObjectMapper()
        );
        LearningMaterialEntity material = new LearningMaterialEntity();
        material.setId(11L);
        material.setOwnerId(7L);
        material.setTitle("Vector Material");
        MaterialChunkEntity chunk = new MaterialChunkEntity();
        chunk.setId(13L);
        chunk.setMaterialId(11L);
        chunk.setChunkIndex(0);
        chunk.setChunkText("TCP protocol text");

        client.upsertChunks(7L, material, List.of(chunk), Map.of(13L, List.of(0.1, 0.2, 0.3)));
        List<VectorSearchResult> results = client.search(7L, 11L, List.of(0.1, 0.2, 0.3), 5, 0.55);

        assertThat(requests).anyMatch(request -> request.contains("\"points\""));
        assertThat(requests).anyMatch(request -> request.contains("\"key\":\"materialId\""));
        assertThat(results).containsExactly(new VectorSearchResult(11L, 13L, 0.91));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
