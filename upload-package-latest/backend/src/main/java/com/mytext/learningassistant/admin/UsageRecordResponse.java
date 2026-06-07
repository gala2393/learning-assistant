package com.mytext.learningassistant.admin;

/**
 * 管理后台 — 使用记录响应 DTO。
 * 展示一次用户操作的详细信息，包括 token 消耗统计。
 * 管理员可以通过这个数据了解系统的使用情况和成本。
 */
public record UsageRecordResponse(
    Long id,                  // 记录 ID
    Long userId,              // 用户 ID
    String username,          // 用户名
    String action,            // 操作类型（RAG_CHAT/RAG_CHAT_STREAM/MATERIAL_UPLOAD 等）
    String targetType,        // 操作对象类型（RAG_QUESTION/LEARNING_MATERIAL 等）
    Long targetId,            // 操作对象 ID
    String modelName,         // 使用的模型名（如 gpt-4o、deepseek-chat）
    Integer promptTokens,     // 提示词 token 数
    Integer completionTokens, // 回答 token 数
    Integer totalTokens,      // 总 token 数
    String detail,            // 操作详情
    String createdAt          // 操作时间
) {
}
