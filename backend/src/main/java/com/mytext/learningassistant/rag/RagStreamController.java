package com.mytext.learningassistant.rag;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/rag")
public class RagStreamController {

    private final RagService ragService;
    private final ThirdPartyLlmClient thirdPartyLlmClient;
    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

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
    public SseEmitter chatStream(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody ChatRequest request
    ) {
        SseEmitter emitter = new SseEmitter(120_000L);

        executor.execute(() -> {
            try {
                sendEvent(emitter, "status", Map.of("stage", "searching"));

                RagStreamResult result = ragService.chatStream(
                    currentUserId, request,
                    delta -> {
                        try {
                            sendEvent(emitter, "chunk", Map.of("delta", delta));
                        } catch (IOException ignored) {
                        }
                    }
                );

                sendEvent(emitter, "sources", Map.of("sources", result.sources()));
                sendEvent(emitter, "done", Map.of(
                    "questionId", result.questionId(),
                    "conversationId", result.conversationId(),
                    "answer", result.answer()
                ));

                emitter.complete();
            } catch (Exception e) {
                try {
                    sendEvent(emitter, "error", Map.of("message", e.getMessage() == null ? "内部错误" : e.getMessage()));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> emitter.complete());
        emitter.onError(t -> {});

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
            .name(eventName)
            .data(objectMapper.writeValueAsString(data)));
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
