package com.mytext.learningassistant.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleLlmClient(LlmProperties properties) {
        this(properties, new ObjectMapper());
    }

    OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
    }

    @Override
    public Optional<LlmResult> chat(String systemPrompt, String userPrompt, List<LlmImage> images) {
        if (!properties.configured()) {
            return Optional.empty();
        }

        List<LlmImage> safeImages = images == null ? List.of() : images;

        try {
            HttpRequest request = isAnthropicCompatible()
                ? anthropicMessagesRequest(systemPrompt, userPrompt, safeImages)
                : chatCompletionsRequest(systemPrompt, userPrompt, safeImages);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = isAnthropicCompatible()
                ? anthropicContent(root)
                : root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new LlmResult(content.trim(), properties.model()));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    @Override
    public String chatStream(String systemPrompt, String userPrompt, List<LlmImage> images, Consumer<String> onChunk) {
        if (!properties.configured()) {
            return "";
        }
        List<LlmImage> safeImages = images == null ? List.of() : images;
        try {
            HttpRequest request = isAnthropicCompatible()
                ? anthropicStreamRequest(systemPrompt, userPrompt, safeImages)
                : openAiStreamRequest(systemPrompt, userPrompt, safeImages);

            StringBuilder fullContent = new StringBuilder();
            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }

            boolean anthropic = isAnthropicCompatible();
            String pendingEventType = null;
            for (String line : response.body().toList()) {
                if (anthropic) {
                    if (line.startsWith("event: ")) {
                        pendingEventType = line.substring(7).trim();
                        continue;
                    }
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String json = line.substring(6);
                    if ("content_block_delta".equals(pendingEventType)) {
                        String delta = parseAnthropicDelta(json);
                        if (!delta.isEmpty()) {
                            fullContent.append(delta);
                            onChunk.accept(delta);
                        }
                    }
                    pendingEventType = null;
                } else {
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String json = line.substring(6);
                    if ("[DONE]".equals(json.trim())) {
                        break;
                    }
                    String delta = parseOpenAiDelta(json);
                    if (!delta.isEmpty()) {
                        fullContent.append(delta);
                        onChunk.accept(delta);
                    }
                }
            }
            return fullContent.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private HttpRequest openAiStreamRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(chatCompletionsUri())
            .timeout(properties.timeout())
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(openAiStreamBody(systemPrompt, userPrompt, images)))
            .build();
    }

    private String openAiStreamBody(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", 0.2);
        body.put("stream", true);
        Object userContent = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : openAiMultimodalContent(userPrompt, images);
        body.put("messages", List.of(
            Map.of("role", "system", "content", nullToEmpty(systemPrompt)),
            Map.of("role", "user", "content", userContent)
        ));
        return objectMapper.writeValueAsString(body);
    }

    private HttpRequest anthropicStreamRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(anthropicMessagesUri())
            .timeout(properties.timeout())
            .header("x-api-key", properties.apiKey())
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(anthropicStreamBody(systemPrompt, userPrompt, images)))
            .build();
    }

    private String anthropicStreamBody(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("max_tokens", 2048);
        body.put("temperature", 0.2);
        body.put("stream", true);
        body.put("system", nullToEmpty(systemPrompt));
        Object userContent = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : anthropicMultimodalContent(userPrompt, images);
        body.put("messages", List.of(
            Map.of("role", "user", "content", userContent)
        ));
        return objectMapper.writeValueAsString(body);
    }

    private String parseOpenAiDelta(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("delta").path("content").asText("");
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private String parseAnthropicDelta(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root.path("delta").path("text").asText("");
        } catch (Exception ignored) {
        }
        return "";
    }

    private HttpRequest chatCompletionsRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(chatCompletionsUri())
            .timeout(properties.timeout())
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(chatCompletionsBody(systemPrompt, userPrompt, images)))
            .build();
    }

    private HttpRequest anthropicMessagesRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(anthropicMessagesUri())
            .timeout(properties.timeout())
            .header("x-api-key", properties.apiKey())
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(anthropicMessagesBody(systemPrompt, userPrompt, images)))
            .build();
    }

    private URI chatCompletionsUri() {
        String baseUrl = properties.baseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith("/v1")) {
            return URI.create(baseUrl + "/chat/completions");
        }
        return URI.create(baseUrl + "/v1/chat/completions");
    }

    private URI anthropicMessagesUri() {
        String baseUrl = normalizedBaseUrl();
        if (baseUrl.endsWith("/v1")) {
            return URI.create(baseUrl + "/messages");
        }
        return URI.create(baseUrl + "/v1/messages");
    }

    private String chatCompletionsBody(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", 0.2);
        Object userContent = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : openAiMultimodalContent(userPrompt, images);
        body.put("messages", List.of(
            Map.of("role", "system", "content", nullToEmpty(systemPrompt)),
            Map.of("role", "user", "content", userContent)
        ));
        return objectMapper.writeValueAsString(body);
    }

    private String anthropicMessagesBody(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("max_tokens", 2048);
        body.put("temperature", 0.2);
        body.put("system", nullToEmpty(systemPrompt));
        Object userContent = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : anthropicMultimodalContent(userPrompt, images);
        body.put("messages", List.of(
            Map.of("role", "user", "content", userContent)
        ));
        return objectMapper.writeValueAsString(body);
    }

    private List<Map<String, Object>> openAiMultimodalContent(String text, List<LlmImage> images) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", nullToEmpty(text)));
        for (LlmImage image : images) {
            parts.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + image.mediaType() + ";base64," + image.base64Data())
            ));
        }
        return parts;
    }

    private List<Map<String, Object>> anthropicMultimodalContent(String text, List<LlmImage> images) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "text", "text", nullToEmpty(text)));
        for (LlmImage image : images) {
            parts.add(Map.of(
                "type", "image",
                "source", Map.of("type", "base64", "media_type", image.mediaType(), "data", image.base64Data())
            ));
        }
        return parts;
    }

    private String anthropicContent(JsonNode root) {
        StringBuilder content = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText(""))) {
                String text = block.path("text").asText("");
                if (!text.isBlank()) {
                    if (!content.isEmpty()) {
                        content.append("\n");
                    }
                    content.append(text);
                }
            }
        }
        return content.toString();
    }

    private boolean isAnthropicCompatible() {
        return normalizedBaseUrl().contains("/anthropic");
    }

    private String normalizedBaseUrl() {
        String baseUrl = properties.baseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
