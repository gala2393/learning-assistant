package com.mytext.learningassistant.rag;

import jakarta.validation.constraints.NotNull;

/**
 * 生成学习资料摘要的请求体。
 * <p>
 * 用于 POST /api/rag/summarize 接口。
 *
 * @param materialId 需要生成摘要的学习资料 ID，不能为空
 */
public record SummarizeRequest(
    @NotNull(message = "materialId is required")
    Long materialId,
    String summaryType
) {
}
