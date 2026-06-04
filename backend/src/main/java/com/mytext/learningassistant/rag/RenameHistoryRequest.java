package com.mytext.learningassistant.rag;

import jakarta.validation.constraints.NotBlank;

/**
 * 重命名对话历史标题的请求体。
 * <p>
 * 用于 PATCH /api/rag/history/{id}/title 接口。
 *
 * @param title 新的对话标题，不能为空白字符串
 */
public record RenameHistoryRequest(
    @NotBlank String title
) {
}
