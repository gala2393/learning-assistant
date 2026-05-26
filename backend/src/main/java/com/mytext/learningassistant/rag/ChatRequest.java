package com.mytext.learningassistant.rag;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
    @NotBlank(message = "闂涓嶈兘涓虹┖")
    @Size(max = 2000, message = "闂涓嶈兘瓒呰繃 2000 瀛?")
    String question,
    Long materialId,
    String mode,
    Long chunkId,
    Integer currentPageNo,
    List<Long> currentPageChunkIds,
    String selectedText,
    String answerStyle,
    List<ChatMessage> history
) {
}
