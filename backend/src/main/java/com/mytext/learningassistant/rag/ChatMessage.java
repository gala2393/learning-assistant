package com.mytext.learningassistant.rag;

/**
 * 聊天消息记录，表示对话中的一条消息。
 *
 * <p>用于在多轮对话中传递历史消息上下文，让 LLM 能够理解之前的对话内容。</p>
 *
 * @param role    消息角色，通常为 "user"（用户）或 "assistant"（AI 助手）
 * @param content 消息的文本内容
 */
public record ChatMessage(String role, String content) {
}
