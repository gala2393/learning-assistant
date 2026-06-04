package com.mytext.learningassistant.rag;

/**
 * 会话消息响应记录 —— 表示对话中的一条消息（用户问题或 AI 回答）。
 *
 * <p>用于在历史详情页展示完整的对话上下文。</p>
 *
 * @param id   关联的问答记录 ID
 * @param role 消息角色："user"（用户）或 "assistant"（AI 助手）
 * @param text 消息文本内容
 */
public record RagHistoryMessageResponse(
    Long id,
    String role,
    String text
) {
}
