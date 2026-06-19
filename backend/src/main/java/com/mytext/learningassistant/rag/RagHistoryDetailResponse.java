package com.mytext.learningassistant.rag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * RAG 历史详情响应记录 —— 展示一条会话的完整信息，包括所有消息和引用来源。
 *
 * <p>当用户点击历史记录查看详情时，会返回此结构，包含整个会话的对话轮次、
 * 最新回答的引用来源、是否已收藏等信息。</p>
 *
 * @param id             会话中最新一条问答的 ID
 * @param conversationId 会话 ID
 * @param title          会话标题
 * @param question       最新的问题文本
 * @param answer         最新的回答文本
 * @param createdAt      创建时间
 * @param messages       会话中的所有消息（用户问题 + AI 回答的交替列表）
 * @param sources        最新回答引用的资料来源
 * @param favoriteId     收藏记录 ID（未收藏时为 null）
 * @param favorite       是否已收藏
 * @param pinned         是否已置顶
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RagHistoryDetailResponse(
    Long id,
    Long conversationId,
    String title,
    String question,
    String answer,
    String createdAt,
    List<RagHistoryMessageResponse> messages,
    List<RagSourceResponse> sources,
    List<RetrievalDebugEntry> retrievalDebug,
    Long favoriteId,
    boolean favorite,
    boolean pinned
) {
}
