package com.mytext.learningassistant.llm;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
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
            HttpRequest request = properties.responsesApi()
                ? responsesRequest(systemPrompt, userPrompt, safeImages)
                : isAnthropicCompatible()
                ? anthropicMessagesRequest(systemPrompt, userPrompt, safeImages)
                : chatCompletionsRequest(systemPrompt, userPrompt, safeImages);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            String content = properties.responsesApi()
                ? responsesContent(root)
                : isAnthropicCompatible()
                ? anthropicContent(root)
                : root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                return Optional.empty();
            }
            TokenUsage usage = tokenUsage(root);
            return Optional.of(new LlmResult(
                content.trim(),
                properties.model(),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens()
            ));
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
            HttpRequest request = properties.responsesApi()
                ? responsesStreamRequest(systemPrompt, userPrompt, safeImages)
                : isAnthropicCompatible()
                ? anthropicStreamRequest(systemPrompt, userPrompt, safeImages)
                : openAiStreamRequest(systemPrompt, userPrompt, safeImages);

            StringBuilder fullContent = new StringBuilder();
            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }

            boolean responses = properties.responsesApi();
            boolean anthropic = isAnthropicCompatible();
            String pendingEventType = null;
            try (java.util.stream.Stream<String> lines = response.body()) {
                Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (responses) {
                        if (!line.startsWith("data: ")) {
                            continue;
                        }
                        String json = line.substring(6);
                        if ("[DONE]".equals(json.trim())) {
                            break;
                        }
                        String delta = parseResponsesDelta(json);
                        if (!delta.isEmpty()) {
                            fullContent.append(delta);
                            onChunk.accept(delta);
                        }
                    } else if (anthropic) {
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
            }
            return fullContent.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private HttpRequest responsesStreamRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(responsesUri())
            .timeout(properties.timeout())
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(responsesBody(systemPrompt, userPrompt, images, true)))
            .build();
    }

    private HttpRequest openAiStreamRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(chatCompletionsUri())
            .timeout(properties.timeout())
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
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
            .header("Accept", "text/event-stream")
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

    private String parseResponsesDelta(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if ("response.output_text.delta".equals(root.path("type").asText(""))) {
                return root.path("delta").asText("");
            }
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

    private HttpRequest responsesRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(responsesUri())
            .timeout(properties.timeout())
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(responsesBody(systemPrompt, userPrompt, images, false)))
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

    private URI responsesUri() {
        String baseUrl = normalizedBaseUrl();
        if (baseUrl.endsWith("/responses")) {
            return URI.create(baseUrl);
        }
        if (baseUrl.endsWith("/v1")) {
            return URI.create(baseUrl + "/responses");
        }
        return URI.create(baseUrl + "/v1/responses");
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

    private String responsesBody(String systemPrompt, String userPrompt, List<LlmImage> images, boolean stream) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("instructions", nullToEmpty(systemPrompt));
        body.put("temperature", 0.2);
        if (stream) {
            body.put("stream", true);
        }
        Object input = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : List.of(Map.of(
                "role", "user",
                "content", responsesMultimodalContent(userPrompt, images)
            ));
        body.put("input", input);
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

    private List<Map<String, Object>> responsesMultimodalContent(String text, List<LlmImage> images) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("type", "input_text", "text", nullToEmpty(text)));
        for (LlmImage image : images) {
            parts.add(Map.of(
                "type", "input_image",
                "image_url", "data:" + image.mediaType() + ";base64," + image.base64Data()
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

    private String responsesContent(JsonNode root) {
        String outputText = root.path("output_text").asText("");
        if (!outputText.isBlank()) {
            return outputText;
        }
        StringBuilder content = new StringBuilder();
        for (JsonNode item : root.path("output")) {
            for (JsonNode block : item.path("content")) {
                String type = block.path("type").asText("");
                if ("output_text".equals(type) || "text".equals(type)) {
                    String text = block.path("text").asText("");
                    if (!text.isBlank()) {
                        if (!content.isEmpty()) {
                            content.append("\n");
                        }
                        content.append(text);
                    }
                }
            }
        }
        return content.toString();
    }

    private TokenUsage tokenUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return new TokenUsage(null, null, null);
        }
        Integer promptTokens = intOrNull(usage, "prompt_tokens");
        Integer completionTokens = intOrNull(usage, "completion_tokens");
        if (promptTokens == null) {
            promptTokens = intOrNull(usage, "input_tokens");
        }
        if (completionTokens == null) {
            completionTokens = intOrNull(usage, "output_tokens");
        }
        Integer totalTokens = intOrNull(usage, "total_tokens");
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
        return new TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    private Integer intOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isNumber() ? value.asInt() : null;
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

    private record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }
}
