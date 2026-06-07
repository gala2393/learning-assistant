package com.mytext.learningassistant.rag;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.common.ApiResponse;
import com.mytext.learningassistant.llm.LlmCompletion;
import com.mytext.learningassistant.llm.ThirdPartyLlmClient;
import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.LearningMaterialRepository;
import com.mytext.learningassistant.material.MaterialChunkEntity;
import com.mytext.learningassistant.material.MaterialChunkRepository;
import com.mytext.learningassistant.security.RateLimitService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * RAG 流式问答控制器。
 * <p>
 * 使用 Server-Sent Events (SSE) 协议实现流式回答，
 * 让用户在 AI 生成回答的过程中实时看到逐字输出的效果，提升交互体验。
 * <p>
 * SSE 事件类型包括：
 * <ul>
 *   <li>{@code status}  - 当前处理阶段（如"正在检索相关资料..."）</li>
 *   <li>{@code chunk}   - 回答文本的逐段推送</li>
 *   <li>{@code sources} - 引用来源列表</li>
 *   <li>{@code done}    - 流式完成，包含完整回答和对话 ID</li>
 *   <li>{@code error}   - 错误信息</li>
 * </ul>
 * <p>
 * 此控制器还提供"推荐问题"接口，根据资料内容自动生成学生可能想问的问题。
 */
@RestController
@RequestMapping("/api/rag")
public class RagStreamController {

    /** RAG 业务服务，处理检索和对话逻辑 */
    private final RagService ragService;
    /** 第三方大语言模型客户端，用于调用 AI 模型 */
    private final ThirdPartyLlmClient thirdPartyLlmClient;
    /** 学习资料数据访问层 */
    private final LearningMaterialRepository learningMaterialRepository;
    /** 资料片段数据访问层 */
    private final MaterialChunkRepository materialChunkRepository;
    /** JSON 序列化工具，用于将 SSE 数据转为 JSON 字符串 */
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;

    /**
     * 构造方法，由 Spring 自动注入所有依赖。
     *
     * @param ragService                    RAG 业务服务
     * @param thirdPartyLlmClient           第三方 LLM 客户端
     * @param learningMaterialRepository    学习资料仓储
     * @param materialChunkRepository       资料片段仓储
     * @param objectMapper                  JSON 序列化工具
     */
    public RagStreamController(
        RagService ragService,
        ThirdPartyLlmClient thirdPartyLlmClient,
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        ObjectMapper objectMapper,
        RateLimitService rateLimitService
    ) {
        this.ragService = ragService;
        this.thirdPartyLlmClient = thirdPartyLlmClient;
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
    }

    /**
     * 流式问答接口。
     * <p>
     * 使用 SSE (Server-Sent Events) 协议推送回答内容。整个流程如下：
     * <ol>
     *   <li>发送 {@code status} 事件，通知前端当前处理阶段</li>
     *   <li>发送 SSE 心跳填充（防止代理缓冲）</li>
     *   <li>调用 {@code RagService.chatStream} 执行 RAG 流程，每当 LLM 返回一个片段时，通过回调推送给前端</li>
     *   <li>发送 {@code sources} 事件，推送引用来源</li>
     *   <li>发送 {@code done} 事件，携带完整的回答文本和对话 ID</li>
     * </ol>
     *
     * @param currentUserId 当前登录用户 ID（由拦截器注入）
     * @param request       问答请求体，包含问题文本、资料 ID、对话模式等
     * @return SSE 流式响应体
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody ChatRequest request,
        HttpServletRequest httpRequest
    ) {
        rateLimitService.checkRagChat(rateIdentity(currentUserId, httpRequest));
        // 创建流式响应体：在输出流上逐步写入 SSE 事件
        StreamingResponseBody stream = outputStream -> {
            try {
                // 判断是否为资料问答模式，显示不同的初始状态提示
                boolean materialChat = "MATERIAL".equalsIgnoreCase(String.valueOf(request.mode()).trim());
                sendEvent(outputStream, "status", Map.of(
                    "stage", materialChat ? "searching" : "thinking",
                    "message", materialChat ? "正在检索相关资料..." : "正在准备回答..."
                ));

                // 发送 SSE 心跳填充，防止 Nginx 等反向代理对 SSE 响应进行缓冲
                sendStreamPadding(outputStream);

                // 调用 RAG 流式问答，每当 LLM 返回文本片段时，通过回调函数实时推送
                RagStreamResult result = ragService.chatStream(
                    currentUserId, request,
                    delta -> {
                        try {
                            // 将文本片段拆分为更小的 chunk 事件发送
                            sendChunkEvents(outputStream, delta);
                        } catch (IOException ignored) {
                            // 输出流关闭时忽略异常
                        }
                    }
                );

                // 流式完成：推送引用来源和完成信号
                sendEvent(outputStream, "sources", Map.of("sources", result.sources()));
                sendEvent(outputStream, "done", Map.of(
                    "questionId", result.questionId(),
                    "conversationId", result.conversationId(),
                    "answer", result.answer()
                ));
            } catch (Exception e) {
                // 发生异常时推送错误事件
                try {
                    sendEvent(outputStream, "error", Map.of("message", e.getMessage() == null ? "内部错误" : e.getMessage()));
                } catch (IOException ignored) {
                }
            }
        };

        // 设置 SSE 响应头：禁用缓存、禁用代理缓冲、保持长连接
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache, no-transform")
            .header("X-Accel-Buffering", "no")
            .header("Connection", "keep-alive")
            .body(stream);
    }

    /**
     * 向输出流写入一条 SSE 事件。
     * <p>
     * SSE 格式为：{@code event: <事件名>\ndata: <JSON数据>\n\n}
     *
     * @param outputStream 输出流
     * @param eventName    事件名称（如 "chunk"、"done"、"error"）
     * @param data         事件数据，会被序列化为 JSON
     * @throws IOException 写入失败时抛出
     */
    private void sendEvent(OutputStream outputStream, String eventName, Object data) throws IOException {
        String payload = "event: " + eventName + "\n"
            + "data: " + objectMapper.writeValueAsString(data) + "\n\n";
        outputStream.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /**
     * 将一段文本拆分为更小的 chunk 事件逐个发送。
     * <p>
     * 每个 chunk 最多 10 个字符，遇到标点符号或换行符时提前断开，
     * 每个 chunk 之间有 18 毫秒的延迟，模拟"打字机"效果。
     *
     * @param outputStream 输出流
     * @param delta        待发送的文本片段
     * @throws IOException 写入失败时抛出
     */
    private void sendChunkEvents(OutputStream outputStream, String delta) throws IOException {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < delta.length()) {
            // 找到下一个合适的断点（标点或达到最大长度）
            int next = nextChunkEnd(delta, index);
            sendEvent(outputStream, "chunk", Map.of("delta", delta.substring(index, next)));
            index = next;
            if (index < delta.length()) {
                sleepBetweenChunks();
            }
        }
    }

    /**
     * 计算下一个 chunk 的结束位置。
     * <p>
     * 从 start 开始，向前最多扫描 10 个字符，如果遇到标点符号或换行符则在此处断开，
     * 否则取最多 10 个字符作为一段。
     *
     * @param value 完整文本
     * @param start 起始位置
     * @return 下一个 chunk 的结束位置（不含）
     */
    private int nextChunkEnd(String value, int start) {
        int maxEnd = Math.min(value.length(), start + 10);
        for (int i = start + 1; i <= maxEnd; i++) {
            char c = value.charAt(i - 1);
            // 遇到中英文标点符号或换行符时，在此处断开
            if (c == '\n' || c == '\r' || c == '。' || c == '，' || c == '；'
                || c == '：' || c == '！' || c == '？' || c == '.' || c == ','
                || c == ';' || c == ':' || c == '!' || c == '?') {
                return i;
            }
        }
        return maxEnd;
    }

    /**
     * 在两个 chunk 之间短暂休眠，模拟逐字输出效果。
     * 休眠时间为 18 毫秒。
     */
    private void sleepBetweenChunks() {
        try {
            Thread.sleep(18);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 发送 SSE 心跳填充数据。
     * <p>
     * 通过写入 2048 字节的空注释行（以 ":" 开头），强制 Nginx 等代理立即发送数据，
     * 防止它们缓冲 SSE 响应导致前端无法实时收到事件。
     *
     * @param outputStream 输出流
     * @throws IOException 写入失败时抛出
     */
    private void sendStreamPadding(OutputStream outputStream) throws IOException {
        String padding = ":" + " ".repeat(2048) + "\n\n";
        outputStream.write(padding.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /**
     * 根据学习资料内容生成推荐问题。
     * <p>
     * 优先使用指定的 chunk 内容作为上下文，如果没有则使用资料标题。
     * 调用 LLM 生成 3 个学生可能提出的问题。
     *
     * @param userId     当前登录用户 ID
     * @param materialId 资料 ID（必填）
     * @param chunkId    资料片段 ID（可选，用于精确指定上下文）
     * @return 推荐问题列表（最多 3 个）
     */
    @GetMapping("/suggest-questions")
    public ApiResponse<List<String>> suggestQuestions(
        @RequestAttribute("currentUserId") long userId,
        @RequestParam long materialId,
        @RequestParam(required = false) Long chunkId
    ) {
        // 验证资料归属当前用户
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElse(null);
        if (material == null) {
            return ApiResponse.ok(List.of());
        }

        // 如果指定了 chunkId，获取该片段的文本内容作为上下文
        String chunkText = "";
        if (chunkId != null) {
            chunkText = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId).stream()
                .filter(c -> c.getId().equals(chunkId))
                .findFirst()
                .map(MaterialChunkEntity::getChunkText)
                .orElse("");
        }
        // 没有片段内容时，使用资料标题作为上下文
        if (chunkText.isBlank()) {
            chunkText = material.getTitle();
        }

        // 截取前 500 字符作为 LLM 输入，避免过长
        String context = chunkText.length() > 500 ? chunkText.substring(0, 500) : chunkText;
        // 构建提示词：要求 LLM 基于资料片段生成 3 个问题
        String prompt = "基于以下学习资料片段，生成3个学生可能会问的问题。只输出问题，每行一个，不要编号。\n\n资料片段：\n" + context;

        // 调用 LLM 生成问题，解析为列表（取前 3 行非空行）
        List<String> questions = thirdPartyLlmClient
            .answer(prompt, List.of())
            .map(LlmCompletion::content)
            .map(content -> content.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(3)
                .toList())
            .orElse(List.of());

        return ApiResponse.ok(questions);
    }

    private String rateIdentity(long currentUserId, HttpServletRequest request) {
        return currentUserId + ":" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",", 2)[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
