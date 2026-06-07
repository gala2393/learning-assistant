package com.mytext.learningassistant.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleLlmClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void disabledClientReturnsEmpty() {
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(false, "", "", "test-model", "chat-completions", Duration.ofSeconds(1))
        );

        assertThat(client.chat("system", "user")).isEmpty();
    }

    @Test
    void sendsChatCompletionRequestAndReadsAssistantMessage() throws Exception {
        server = startServer(200, """
            {
              "choices": [
                { "message": { "content": "LLM answer" } }
              ]
            }
            """);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(true, baseUrl(), "test-key", "test-model", "chat-completions", Duration.ofSeconds(2))
        );

        Optional<LlmResult> result = client.chat("You are helpful.", "Question?");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().content()).isEqualTo("LLM answer");
        assertThat(result.orElseThrow().modelName()).isEqualTo("test-model");
    }

    @Test
    void sendsAnthropicMessagesRequestAndReadsTextContent() throws Exception {
        server = startServer("/anthropic/v1/messages", 200, """
            {
              "content": [
                { "type": "text", "text": "Anthropic answer" }
              ]
            }
            """);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(true, baseUrl() + "/anthropic", "test-key", "deepseek-v4-pro", "chat-completions", Duration.ofSeconds(2))
        );

        Optional<LlmResult> result = client.chat("You are helpful.", "Question?");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().content()).isEqualTo("Anthropic answer");
        assertThat(result.orElseThrow().modelName()).isEqualTo("deepseek-v4-pro");
    }

    @Test
    void failedProviderCallReturnsEmptyForFallback() throws Exception {
        server = startServer(500, """
            { "error": "temporary failure" }
            """);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(true, baseUrl(), "test-key", "test-model", "chat-completions", Duration.ofSeconds(2))
        );

        assertThat(client.chat("system", "user")).isEmpty();
    }

    @Test
    void chatStreamForwardsOpenAiDeltasBeforeProviderCompletes() throws Exception {
        CountDownLatch firstChunkReceived = new CountDownLatch(1);
        AtomicBoolean firstChunkReachedClientBeforeSecondChunk = new AtomicBoolean(false);
        server = startOpenAiStreamingServer(firstChunkReceived, firstChunkReachedClientBeforeSecondChunk);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(true, baseUrl(), "test-key", "test-model", "chat-completions", Duration.ofSeconds(5))
        );
        List<String> chunks = new ArrayList<>();

        String answer = client.chatStream("system", "user", List.of(), delta -> {
            chunks.add(delta);
            if ("Hello ".equals(delta)) {
                firstChunkReceived.countDown();
            }
        });

        assertThat(firstChunkReachedClientBeforeSecondChunk).isTrue();
        assertThat(chunks).containsExactly("Hello ", "world");
        assertThat(answer).isEqualTo("Hello world");
    }

    @Test
    void sendsResponsesRequestAndReadsOutputText() throws Exception {
        server = startServer("/v1/responses", 200, """
            {
              "output": [
                {
                  "type": "message",
                  "content": [
                    { "type": "output_text", "text": "Responses answer" }
                  ]
                }
              ]
            }
            """);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(true, baseUrl(), "test-key", "test-model", "responses", Duration.ofSeconds(2))
        );

        Optional<LlmResult> result = client.chat("You are helpful.", "Question?");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().content()).isEqualTo("Responses answer");
        assertThat(result.orElseThrow().modelName()).isEqualTo("test-model");
    }

    @Test
    void chatStreamForwardsResponsesDeltasBeforeProviderCompletes() throws Exception {
        CountDownLatch firstChunkReceived = new CountDownLatch(1);
        AtomicBoolean firstChunkReachedClientBeforeSecondChunk = new AtomicBoolean(false);
        server = startResponsesStreamingServer(firstChunkReceived, firstChunkReachedClientBeforeSecondChunk);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(
            new LlmProperties(true, baseUrl(), "test-key", "test-model", "responses", Duration.ofSeconds(5))
        );
        List<String> chunks = new ArrayList<>();

        String answer = client.chatStream("system", "user", List.of(), delta -> {
            chunks.add(delta);
            if ("Hello ".equals(delta)) {
                firstChunkReceived.countDown();
            }
        });

        assertThat(firstChunkReachedClientBeforeSecondChunk).isTrue();
        assertThat(chunks).containsExactly("Hello ", "world");
        assertThat(answer).isEqualTo("Hello world");
    }

    private HttpServer startServer(int status, String body) throws IOException {
        return startServer("/v1/chat/completions", status, body);
    }

    private HttpServer startServer(String path, int status, String body) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext(path, exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    private HttpServer startOpenAiStreamingServer(
        CountDownLatch firstChunkReceived,
        AtomicBoolean firstChunkReachedClientBeforeSecondChunk
    ) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write("""
                    data: {"choices":[{"delta":{"content":"Hello "}}]}

                    """.getBytes(StandardCharsets.UTF_8));
                body.flush();
                firstChunkReachedClientBeforeSecondChunk.set(firstChunkReceived.await(1, TimeUnit.SECONDS));
                body.write("""
                    data: {"choices":[{"delta":{"content":"world"}}]}

                    data: [DONE]

                    """.getBytes(StandardCharsets.UTF_8));
                body.flush();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private HttpServer startResponsesStreamingServer(
        CountDownLatch firstChunkReceived,
        AtomicBoolean firstChunkReachedClientBeforeSecondChunk
    ) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/responses", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write("""
                    data: {"type":"response.output_text.delta","delta":"Hello "}

                    """.getBytes(StandardCharsets.UTF_8));
                body.flush();
                firstChunkReachedClientBeforeSecondChunk.set(firstChunkReceived.await(1, TimeUnit.SECONDS));
                body.write("""
                    data: {"type":"response.output_text.delta","delta":"world"}

                    data: {"type":"response.completed"}

                    """.getBytes(StandardCharsets.UTF_8));
                body.flush();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
