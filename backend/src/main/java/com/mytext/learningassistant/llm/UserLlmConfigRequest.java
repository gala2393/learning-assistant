package com.mytext.learningassistant.llm;

/**
 * 用户 LLM 配置的请求体（Request DTO），用于接收前端提交的配置数据。
 *
 * <p>职责：
 * <ul>
 *   <li>在创建或更新用户自定义 LLM 配置时，封装前端传入的参数。</li>
 *   <li>使用 Java record 定义，自动生成构造方法、getter、equals、hashCode 和 toString。</li>
 * </ul>
 *
 * @param id          配置 ID。创建新配置时为 null，更新已有配置时传入对应 ID
 * @param enabled     是否启用该配置
 * @param displayName 用户自定义的配置显示名称，便于在前端列表中区分多条配置
 * @param baseUrl     LLM 服务的 API 基础地址，例如 https://api.openai.com/v1
 * @param apiKey      调用 LLM 服务所需的 API 密钥
 * @param model       要使用的模型名称，例如 gpt-4o、deepseek-chat 等
 */
public record UserLlmConfigRequest(
    /** 配置 ID，新建时为 null，更新时为已有记录的 ID */
    Long id,
    /** 是否启用该配置 */
    boolean enabled,
    /** 用户自定义的显示名称 */
    String displayName,
    /** LLM 服务的 API 基础地址 */
    String baseUrl,
    /** API 密钥 */
    String apiKey,
    /** 模型名称 */
    String model
) {
}
