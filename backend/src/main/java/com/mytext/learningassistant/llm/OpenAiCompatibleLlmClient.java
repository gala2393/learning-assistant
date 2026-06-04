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

/**
 * 兼容 OpenAI 接口的 LLM 客户端实现
 *
 * 实现了 LlmClient 接口，支持多种 LLM API 格式：
 * - OpenAI Chat Completions API（默认）
 * - OpenAI Responses API
 * - Anthropic Messages API（通过 URL 中包含 "/anthropic" 自动识别）
 *
 * 支持同步和流式两种对话方式，以及多模态（文本+图片）输入。
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    /** LLM 配置属性 */
    private final LlmProperties properties;

    /** JSON 序列化/反序列化工具 */
    private final ObjectMapper objectMapper;

    /** HTTP 客户端，用于发送 API 请求 */
    private final HttpClient httpClient;

    /**
     * 公开构造函数（使用默认 ObjectMapper）
     *
     * @param properties LLM 配置属性
     */
    public OpenAiCompatibleLlmClient(LlmProperties properties) {
        this(properties, new ObjectMapper());
    }

    /**
     * 内部构造函数（支持注入自定义 ObjectMapper，便于测试）
     *
     * @param properties   LLM 配置属性
     * @param objectMapper 自定义的 ObjectMapper 实例
     */
    OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // 创建 HTTP 客户端，设置连接超时时间
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.timeout())
            .build();
    }

    /**
     * 同步对话方法（支持图片输入）
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户输入的文本
     * @param images       用户上传的图片列表
     * @return LlmResult 对象的 Optional 包装，失败时返回空
     */
    @Override
    public Optional<LlmResult> chat(String systemPrompt, String userPrompt, List<LlmImage> images) {
        // 检查 LLM 是否已配置
        if (!properties.configured()) {
            return Optional.empty();
        }

        // 确保图片列表不为 null
        List<LlmImage> safeImages = images == null ? List.of() : images;

        try {
            // 根据 API 格式选择不同的请求构建方式
            HttpRequest request = properties.responsesApi()
                ? responsesRequest(systemPrompt, userPrompt, safeImages)
                : isAnthropicCompatible()
                ? anthropicMessagesRequest(systemPrompt, userPrompt, safeImages)
                : chatCompletionsRequest(systemPrompt, userPrompt, safeImages);

            // 发送 HTTP 请求并获取响应
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // 检查 HTTP 状态码是否成功（2xx）
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return Optional.empty();
            }

            // 解析 JSON 响应
            JsonNode root = objectMapper.readTree(response.body());

            // 根据 API 格式提取内容
            String content = properties.responsesApi()
                ? responsesContent(root)
                : isAnthropicCompatible()
                ? anthropicContent(root)
                : root.path("choices").path(0).path("message").path("content").asText("");

            // 如果内容为空，返回空结果
            if (content.isBlank()) {
                return Optional.empty();
            }

            // 提取 token 使用统计
            TokenUsage usage = tokenUsage(root);

            // 构建并返回成功结果
            return Optional.of(new LlmResult(
                content.trim(),
                properties.model(),
                usage.promptTokens(),
                usage.completionTokens(),
                usage.totalTokens()
            ));
        } catch (Exception exception) {
            // 发生任何异常都返回空结果
            return Optional.empty();
        }
    }

    /**
     * 流式对话方法（支持图片输入）
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户输入的文本
     * @param images       用户上传的图片列表
     * @param onChunk      接收流式数据块的回调函数
     * @return 完整的对话结果字符串
     */
    @Override
    public String chatStream(String systemPrompt, String userPrompt, List<LlmImage> images, Consumer<String> onChunk) {
        // 检查 LLM 是否已配置
        if (!properties.configured()) {
            return "";
        }

        // 确保图片列表不为 null
        List<LlmImage> safeImages = images == null ? List.of() : images;

        try {
            // 根据 API 格式选择不同的流式请求构建方式
            HttpRequest request = properties.responsesApi()
                ? responsesStreamRequest(systemPrompt, userPrompt, safeImages)
                : isAnthropicCompatible()
                ? anthropicStreamRequest(systemPrompt, userPrompt, safeImages)
                : openAiStreamRequest(systemPrompt, userPrompt, safeImages);

            // 用于累积完整响应内容
            StringBuilder fullContent = new StringBuilder();

            // 发送流式请求，使用行流处理器
            HttpResponse<java.util.stream.Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

            // 检查 HTTP 状态码
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return "";
            }

            // 根据 API 格式解析流式响应
            boolean responses = properties.responsesApi();
            boolean anthropic = isAnthropicCompatible();
            String pendingEventType = null;

            // 逐行处理流式响应
            try (java.util.stream.Stream<String> lines = response.body()) {
                Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();

                    if (responses) {
                        // 处理 OpenAI Responses API 格式
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
                            onChunk.accept(delta);  // 回调通知调用方
                        }
                    } else if (anthropic) {
                        // 处理 Anthropic Messages API 格式
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
                                onChunk.accept(delta);  // 回调通知调用方
                            }
                        }
                        pendingEventType = null;
                    } else {
                        // 处理 OpenAI Chat Completions 格式
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
                            onChunk.accept(delta);  // 回调通知调用方
                        }
                    }
                }
            }
            return fullContent.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 构建 Responses API 流式请求
     */
    private HttpRequest responsesStreamRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(responsesUri())
            .timeout(properties.timeout())
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(responsesBody(systemPrompt, userPrompt, images, true)))
            .build();
    }

    /**
     * 构建 OpenAI Chat Completions 流式请求
     */
    private HttpRequest openAiStreamRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(chatCompletionsUri())
            .timeout(properties.timeout())
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(openAiStreamBody(systemPrompt, userPrompt, images)))
            .build();
    }

    /**
     * 构建 OpenAI Chat Completions 流式请求体
     */
    private String openAiStreamBody(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", 0.2);
        body.put("stream", true);  // 启用流式输出

        // 根据是否有图片选择不同的内容格式
        Object userContent = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : openAiMultimodalContent(userPrompt, images);

        body.put("messages", List.of(
            Map.of("role", "system", "content", nullToEmpty(systemPrompt)),
            Map.of("role", "user", "content", userContent)
        ));
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 构建 Anthropic Messages API 流式请求
     */
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

    /**
     * 构建 Anthropic Messages API 流式请求体
     */
    private String anthropicStreamBody(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("max_tokens", 2048);
        body.put("temperature", 0.2);
        body.put("stream", true);  // 启用流式输出
        body.put("system", nullToEmpty(systemPrompt));

        // 根据是否有图片选择不同的内容格式
        Object userContent = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : anthropicMultimodalContent(userPrompt, images);

        body.put("messages", List.of(
            Map.of("role", "user", "content", userContent)
        ));
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 解析 OpenAI Chat Completions 流式响应中的增量内容
     */
    private String parseOpenAiDelta(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("delta").path("content").asText("");
            }
        } catch (Exception ignored) {
            // 解析失败时返回空字符串
        }
        return "";
    }

    /**
     * 解析 Anthropic Messages API 流式响应中的增量内容
     */
    private String parseAnthropicDelta(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return root.path("delta").path("text").asText("");
        } catch (Exception ignored) {
            // 解析失败时返回空字符串
        }
        return "";
    }

    /**
     * 解析 OpenAI Responses API 流式响应中的增量内容
     */
    private String parseResponsesDelta(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            // 只处理 output_text.delta 类型的事件
            if ("response.output_text.delta".equals(root.path("type").asText(""))) {
                return root.path("delta").asText("");
            }
        } catch (Exception ignored) {
            // 解析失败时返回空字符串
        }
        return "";
    }

    /**
     * 构建 OpenAI Chat Completions 同步请求
     */
    private HttpRequest chatCompletionsRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(chatCompletionsUri())
            .timeout(properties.timeout())
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(chatCompletionsBody(systemPrompt, userPrompt, images)))
            .build();
    }

    /**
     * 构建 Anthropic Messages API 同步请求
     */
    private HttpRequest anthropicMessagesRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(anthropicMessagesUri())
            .timeout(properties.timeout())
            .header("x-api-key", properties.apiKey())
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(anthropicMessagesBody(systemPrompt, userPrompt, images)))
            .build();
    }

    /**
     * 构建 OpenAI Responses API 同步请求
     */
    private HttpRequest responsesRequest(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        return HttpRequest.newBuilder(responsesUri())
            .timeout(properties.timeout())
            .header("Authorization", "Bearer " + properties.apiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(responsesBody(systemPrompt, userPrompt, images, false)))
            .build();
    }

    /**
     * 构建 Chat Completions API 的 URI
     */
    private URI chatCompletionsUri() {
        String baseUrl = properties.baseUrl();
        // 去除末尾的斜杠
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        // 如果已经包含 /v1 后缀，直接追加路径；否则添加 /v1 前缀
        if (baseUrl.endsWith("/v1")) {
            return URI.create(baseUrl + "/chat/completions");
        }
        return URI.create(baseUrl + "/v1/chat/completions");
    }

    /**
     * 构建 Anthropic Messages API 的 URI
     */
    private URI anthropicMessagesUri() {
        String baseUrl = normalizedBaseUrl();
        if (baseUrl.endsWith("/v1")) {
            return URI.create(baseUrl + "/messages");
        }
        return URI.create(baseUrl + "/v1/messages");
    }

    /**
     * 构建 Responses API 的 URI
     */
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

    /**
     * 构建 Chat Completions API 的请求体
     */
    private String chatCompletionsBody(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("temperature", 0.2);

        // 根据是否有图片选择不同的内容格式
        Object userContent = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : openAiMultimodalContent(userPrompt, images);

        body.put("messages", List.of(
            Map.of("role", "system", "content", nullToEmpty(systemPrompt)),
            Map.of("role", "user", "content", userContent)
        ));
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 构建 Anthropic Messages API 的请求体
     */
    private String anthropicMessagesBody(String systemPrompt, String userPrompt, List<LlmImage> images) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("max_tokens", 2048);
        body.put("temperature", 0.2);
        body.put("system", nullToEmpty(systemPrompt));

        // 根据是否有图片选择不同的内容格式
        Object userContent = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : anthropicMultimodalContent(userPrompt, images);

        body.put("messages", List.of(
            Map.of("role", "user", "content", userContent)
        ));
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 构建 Responses API 的请求体
     */
    private String responsesBody(String systemPrompt, String userPrompt, List<LlmImage> images, boolean stream) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("instructions", nullToEmpty(systemPrompt));
        body.put("temperature", 0.2);

        // 如果是流式请求，添加 stream 标志
        if (stream) {
            body.put("stream", true);
        }

        // 根据是否有图片选择不同的输入格式
        Object input = images.isEmpty()
            ? nullToEmpty(userPrompt)
            : List.of(Map.of(
                "role", "user",
                "content", responsesMultimodalContent(userPrompt, images)
            ));
        body.put("input", input);
        return objectMapper.writeValueAsString(body);
    }

    /**
     * 构建 OpenAI 格式的多模态内容（文本+图片）
     */
    private List<Map<String, Object>> openAiMultimodalContent(String text, List<LlmImage> images) {
        List<Map<String, Object>> parts = new ArrayList<>();
        // 添加文本部分
        parts.add(Map.of("type", "text", "text", nullToEmpty(text)));
        // 添加图片部分（Base64 编码）
        for (LlmImage image : images) {
            parts.add(Map.of(
                "type", "image_url",
                "image_url", Map.of("url", "data:" + image.mediaType() + ";base64," + image.base64Data())
            ));
        }
        return parts;
    }

    /**
     * 构建 Anthropic 格式的多模态内容（文本+图片）
     */
    private List<Map<String, Object>> anthropicMultimodalContent(String text, List<LlmImage> images) {
        List<Map<String, Object>> parts = new ArrayList<>();
        // 添加文本部分
        parts.add(Map.of("type", "text", "text", nullToEmpty(text)));
        // 添加图片部分（Base64 编码）
        for (LlmImage image : images) {
            parts.add(Map.of(
                "type", "image",
                "source", Map.of("type", "base64", "media_type", image.mediaType(), "data", image.base64Data())
            ));
        }
        return parts;
    }

    /**
     * 构建 Responses API 格式的多模态内容（文本+图片）
     */
    private List<Map<String, Object>> responsesMultimodalContent(String text, List<LlmImage> images) {
        List<Map<String, Object>> parts = new ArrayList<>();
        // 添加文本部分
        parts.add(Map.of("type", "input_text", "text", nullToEmpty(text)));
        // 添加图片部分（Base64 编码）
        for (LlmImage image : images) {
            parts.add(Map.of(
                "type", "input_image",
                "image_url", "data:" + image.mediaType() + ";base64," + image.base64Data()
            ));
        }
        return parts;
    }

    /**
     * 从 Anthropic 响应中提取文本内容
     */
    private String anthropicContent(JsonNode root) {
        StringBuilder content = new StringBuilder();
        // 遍历所有 content 块，提取 type 为 "text" 的内容
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText(""))) {
                String text = block.path("text").asText("");
                if (!text.isBlank()) {
                    // 如果已有内容，添加换行符分隔
                    if (!content.isEmpty()) {
                        content.append("\n");
                    }
                    content.append(text);
                }
            }
        }
        return content.toString();
    }

    /**
     * 从 Responses API 响应中提取文本内容
     */
    private String responsesContent(JsonNode root) {
        // 优先尝试直接获取 output_text 字段
        String outputText = root.path("output_text").asText("");
        if (!outputText.isBlank()) {
            return outputText;
        }

        // 如果没有 output_text，从 output 数组中提取
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

    /**
     * 从响应中提取 token 使用统计
     */
    private TokenUsage tokenUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        // 如果没有 usage 字段，返回全 null 的统计
        if (usage.isMissingNode() || usage.isNull()) {
            return new TokenUsage(null, null, null);
        }

        // 尝试提取 OpenAI 格式的 token 统计
        Integer promptTokens = intOrNull(usage, "prompt_tokens");
        Integer completionTokens = intOrNull(usage, "completion_tokens");

        // 如果 OpenAI 格式没有，尝试 Anthropic 格式
        if (promptTokens == null) {
            promptTokens = intOrNull(usage, "input_tokens");
        }
        if (completionTokens == null) {
            completionTokens = intOrNull(usage, "output_tokens");
        }

        // 提取总 token 数，如果不存在则计算
        Integer totalTokens = intOrNull(usage, "total_tokens");
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }

        return new TokenUsage(promptTokens, completionTokens, totalTokens);
    }

    /**
     * 安全地从 JSON 节点中提取整数值
     */
    private Integer intOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isNumber() ? value.asInt() : null;
    }

    /**
     * 判断是否使用 Anthropic 兼容的 API
     *
     * 通过检查 baseUrl 是否包含 "/anthropic" 来判断
     */
    private boolean isAnthropicCompatible() {
        return normalizedBaseUrl().contains("/anthropic");
    }

    /**
     * 获取标准化的 baseUrl（去除末尾斜杠）
     */
    private String normalizedBaseUrl() {
        String baseUrl = properties.baseUrl();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    /**
     * 将 null 值转换为空字符串
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Token 使用统计内部记录类
     */
    private record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }
}
