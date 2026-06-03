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

@RestController
@RequestMapping("/api/rag")
public class RagStreamController {

    private final RagService ragService;
    private final ThirdPartyLlmClient thirdPartyLlmClient;
    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final ObjectMapper objectMapper;

    public RagStreamController(
        RagService ragService,
        ThirdPartyLlmClient thirdPartyLlmClient,
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        ObjectMapper objectMapper
    ) {
        this.ragService = ragService;
        this.thirdPartyLlmClient = thirdPartyLlmClient;
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> chatStream(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody ChatRequest request
    ) {
        StreamingResponseBody stream = outputStream -> {
            try {
                boolean materialChat = "MATERIAL".equalsIgnoreCase(String.valueOf(request.mode()).trim());
                sendEvent(outputStream, "status", Map.of(
                    "stage", materialChat ? "searching" : "thinking",
                    "message", materialChat ? "正在检索相关资料..." : "正在准备回答..."
                ));

                sendStreamPadding(outputStream);

                RagStreamResult result = ragService.chatStream(
                    currentUserId, request,
                    delta -> {
                        try {
                            sendChunkEvents(outputStream, delta);
                        } catch (IOException ignored) {
                        }
                    }
                );

                sendEvent(outputStream, "sources", Map.of("sources", result.sources()));
                sendEvent(outputStream, "done", Map.of(
                    "questionId", result.questionId(),
                    "conversationId", result.conversationId(),
                    "answer", result.answer()
                ));
            } catch (Exception e) {
                try {
                    sendEvent(outputStream, "error", Map.of("message", e.getMessage() == null ? "内部错误" : e.getMessage()));
                } catch (IOException ignored) {
                }
            }
        };

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache, no-transform")
            .header("X-Accel-Buffering", "no")
            .header("Connection", "keep-alive")
            .body(stream);
    }

    private void sendEvent(OutputStream outputStream, String eventName, Object data) throws IOException {
        String payload = "event: " + eventName + "\n"
            + "data: " + objectMapper.writeValueAsString(data) + "\n\n";
        outputStream.write(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private void sendChunkEvents(OutputStream outputStream, String delta) throws IOException {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        int index = 0;
        while (index < delta.length()) {
            int next = nextChunkEnd(delta, index);
            sendEvent(outputStream, "chunk", Map.of("delta", delta.substring(index, next)));
            index = next;
            if (index < delta.length()) {
                sleepBetweenChunks();
            }
        }
    }

    private int nextChunkEnd(String value, int start) {
        int maxEnd = Math.min(value.length(), start + 10);
        for (int i = start + 1; i <= maxEnd; i++) {
            char c = value.charAt(i - 1);
            if (c == '\n' || c == '\r' || c == '\u3002' || c == '\uff0c' || c == '\uff1b'
                || c == '\uff1a' || c == '\uff01' || c == '\uff1f' || c == '.' || c == ','
                || c == ';' || c == ':' || c == '!' || c == '?') {
                return i;
            }
        }
        return maxEnd;
    }

    private void sleepBetweenChunks() {
        try {
            Thread.sleep(18);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendStreamPadding(OutputStream outputStream) throws IOException {
        String padding = ":" + " ".repeat(2048) + "\n\n";
        outputStream.write(padding.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        outputStream.flush();
    }

    @GetMapping("/suggest-questions")
    public ApiResponse<List<String>> suggestQuestions(
        @RequestAttribute("currentUserId") long userId,
        @RequestParam long materialId,
        @RequestParam(required = false) Long chunkId
    ) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElse(null);
        if (material == null) {
            return ApiResponse.ok(List.of());
        }

        String chunkText = "";
        if (chunkId != null) {
            chunkText = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId).stream()
                .filter(c -> c.getId().equals(chunkId))
                .findFirst()
                .map(MaterialChunkEntity::getChunkText)
                .orElse("");
        }
        if (chunkText.isBlank()) {
            chunkText = material.getTitle();
        }

        String context = chunkText.length() > 500 ? chunkText.substring(0, 500) : chunkText;
        String prompt = "基于以下学习资料片段，生成3个学生可能会问的问题。只输出问题，每行一个，不要编号。\n\n资料片段：\n" + context;

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
}
