package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * 收藏项响应记录，表示一条收藏的问答记录及其所属会话的消息列表。
 *
 * @param id             收藏记录的 ID
 * @param questionId     被收藏的问答 ID
 * @param conversationId 所属会话的 ID
 * @param question       用户提出的问题文本
 * @param answer         AI 生成的回答文本
 * @param createdAt      收藏时间（格式化后的字符串）
 * @param messages       所属会话的完整消息列表（用于展示对话上下文）
 */
public record FavoriteItemResponse(
    Long id,
    Long questionId,
    Long conversationId,
    String question,
    String answer,
    String createdAt,
    List<RagHistoryMessageResponse> messages
) {
}
