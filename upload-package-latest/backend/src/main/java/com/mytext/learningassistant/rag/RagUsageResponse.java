package com.mytext.learningassistant.rag;

/**
 * RAG 问答模块的使用额度响应体。
 * <p>
 * 用于告知前端用户当天的问答使用情况。
 * 管理员或已配置自定义模型的用户不受限额限制（unlimited = true）。
 *
 * @param dailyLimit     每日总限额（普通用户为 100 次）
 * @param usedToday      今天已使用的问答次数
 * @param remainingToday 今天剩余可用次数，unlimited 用户为 null
 * @param unlimited      是否为无限制用户（管理员或自定义模型用户）
 */
public record RagUsageResponse(
    int dailyLimit,
    long usedToday,
    Long remainingToday,
    boolean unlimited
) {
}
