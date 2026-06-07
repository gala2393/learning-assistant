package com.mytext.learningassistant.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RAG 历史列表项响应记录 —— 在历史记录列表页展示的简要信息。
 *
 * <p>每个列表项代表一个会话（conversation），显示会话的最新问题、回答摘要等。</p>
 *
 * @param id             会话中最新一条问答的 ID
 * @param conversationId 会话 ID
 * @param title          会话标题
 * @param question       最新的问题文本
 * @param answer         最新的回答文本（可能被截断）
 * @param createdAt      创建时间
 * @param favoriteId     收藏记录 ID（未收藏时为 null）
 * @param favorite       是否已收藏
 * @param pinned         是否已置顶
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RagHistoryItemResponse(
    Long id,
    Long conversationId,
    String title,
    String question,
    String answer,
    String createdAt,
    Long favoriteId,
    boolean favorite,
    boolean pinned
) {
}
