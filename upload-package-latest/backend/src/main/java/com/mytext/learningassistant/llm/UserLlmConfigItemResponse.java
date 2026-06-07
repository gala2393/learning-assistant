package com.mytext.learningassistant.llm;

/**
 * 单条用户 LLM 配置的响应体（Response DTO），用于向前端返回某一条配置的摘要信息。
 *
 * <p>职责：
 * <ul>
 *   <li>在用户 LLM 配置列表接口中，表示其中一条配置的概要信息。</li>
 *   <li>出于安全考虑，不返回完整的 API 密钥，仅通过 {@code hasApiKey} 标记是否已填写密钥。</li>
 * </ul>
 *
 * @param id          配置记录的主键 ID
 * @param displayName 用户自定义的配置显示名称
 * @param baseUrl     LLM 服务的 API 基础地址
 * @param model       模型名称
 * @param hasApiKey   是否已填写 API 密钥（true 表示已填写，不返回实际密钥内容）
 * @param active      该配置是否为当前激活的配置
 */
public record UserLlmConfigItemResponse(
    /** 配置记录的主键 ID */
    Long id,
    /** 用户自定义的显示名称 */
    String displayName,
    /** LLM 服务的 API 基础地址 */
    String baseUrl,
    /** 模型名称 */
    String model,
    /** 是否已填写 API 密钥（不暴露实际密钥内容） */
    boolean hasApiKey,
    /** 是否为当前激活的配置 */
    boolean active
) {
}
