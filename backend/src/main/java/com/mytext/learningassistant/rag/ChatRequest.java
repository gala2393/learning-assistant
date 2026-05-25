package com.mytext.learningassistant.rag;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
    @NotBlank(message = "问题不能为空")
    @Size(max = 2000, message = "问题不能超过 2000 字")
    String question,
    Long materialId,
    String mode,
    Long chunkId,
    String selectedText,
    String answerStyle,
    List<ChatMessage> history
) {
}
