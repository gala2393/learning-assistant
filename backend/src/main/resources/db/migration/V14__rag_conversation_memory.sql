-- 保存会话较早轮次的滚动摘要，用于在不无限追加历史原文的情况下维持长期记忆。
CREATE TABLE rag_conversation_memory (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    summary LONGTEXT,
    summarized_question_id BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_rag_conversation_memory_user_conversation (user_id, conversation_id),
    KEY idx_rag_conversation_memory_user (user_id),
    KEY idx_rag_conversation_memory_conversation (conversation_id)
);
