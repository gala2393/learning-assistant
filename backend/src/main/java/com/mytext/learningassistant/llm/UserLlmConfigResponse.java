package com.mytext.learningassistant.llm;

import java.util.List;

/**
 * 用户 LLM 配置页面的完整响应体（Response DTO）。
 *
 * <p>职责：
 * <ul>
 *   <li>封装用户 LLM 配置管理页面所需的全部信息，包括全局状态和配置列表。</li>
 *   <li>前端可通过 {@code enabled} 判断用户是否开启了自定义 LLM，通过 {@code activeConfigId} 确定当前正在使用哪条配置。</li>
 *   <li>出于安全考虑，不返回完整的 API 密钥，仅标记是否已填写。</li>
 * </ul>
 *
 * @param enabled        用户是否已启用自定义 LLM 配置功能（全局开关）
 * @param baseUrl        当前激活配置的 API 基础地址
 * @param model          当前激活配置的模型名称
 * @param hasApiKey      当前激活配置是否已填写 API 密钥
 * @param activeLabel    当前激活配置的显示标签（通常是 "displayName - model" 的组合文本，方便前端展示）
 * @param activeConfigId 当前激活配置的 ID，为 null 表示没有激活的配置
 * @param configs        用户的全部 LLM 配置列表，每条配置以 {@link UserLlmConfigItemResponse} 表示
 */
public record UserLlmConfigResponse(
    /** 用户是否启用自定义 LLM 配置功能 */
    boolean enabled,
    /** 当前激活配置的 API 基础地址 */
    String baseUrl,
    /** 当前激活配置的模型名称 */
    String model,
    /** 当前激活配置是否已填写 API 密钥 */
    boolean hasApiKey,
    /** 当前激活配置的显示标签，用于前端直接展示 */
    String activeLabel,
    /** 当前激活配置的 ID，null 表示没有激活配置 */
    Long activeConfigId,
    /** 用户的全部 LLM 配置列表 */
    List<UserLlmConfigItemResponse> configs
) {
}
