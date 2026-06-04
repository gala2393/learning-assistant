package com.mytext.learningassistant.llm;

/**
 * LLM 状态响应记录类
 *
 * 用于返回系统 LLM 配置状态的 API 响应数据。
 *
 * 使用 Java record 特性，自动生成 getter、equals、hashCode 和 toString 方法。
 */
public record LlmStatusResponse(
    /** LLM 功能是否启用 */
    boolean enabled,
    /** LLM 是否已完整配置（baseUrl、apiKey、model 都已填写） */
    boolean configured,
    /** 状态提示消息，用于向用户展示当前 LLM 的使用状态 */
    String message
) {
}
