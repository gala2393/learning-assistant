package com.mytext.learningassistant.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

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
            new LlmProperties(false, "", "", "test-model", Duration.ofSeconds(1))
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
            new LlmProperties(true, baseUrl(), "test-key", "test-model", Duration.ofSeconds(2))
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
            new LlmProperties(true, baseUrl() + "/anthropic", "test-key", "deepseek-v4-pro", Duration.ofSeconds(2))
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
            new LlmProperties(true, baseUrl(), "test-key", "test-model", Duration.ofSeconds(2))
        );

        assertThat(client.chat("system", "user")).isEmpty();
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

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
