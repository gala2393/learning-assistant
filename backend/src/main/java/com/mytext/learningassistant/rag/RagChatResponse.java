package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * RAG 聊天响应记录 —— 封装一次完整问答的结果。
 *
 * <p>这是 RAG 问答流程的最终输出，包含了问题 ID、回答内容、引用来源等信息。</p>
 *
 * @param questionId     问答记录的 ID（可用于后续查看详情、评价等操作）
 * @param conversationId 所属会话的 ID
 * @param question       用户提出的问题
 * @param answer         AI 生成的回答
 * @param sources        回答引用的资料来源列表（展示给用户，方便溯源）
 * @param createdAt      创建时间（格式化后的字符串）
 */
public record RagChatResponse(
    Long questionId,
    Long conversationId,
    String question,
    String answer,
    List<RagSourceResponse> sources,
    String createdAt
) {
}
