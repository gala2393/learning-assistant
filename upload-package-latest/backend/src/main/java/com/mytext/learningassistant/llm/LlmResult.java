package com.mytext.learningassistant.llm;

/**
 * LLM 结果记录类
 *
 * 用于存储 LLM API 调用的原始返回结果。
 * 与 LlmCompletion 不同，此类不包含是否使用自定义模型的信息。
 *
 * 使用 Java record 特性，自动生成 getter、equals、hashCode 和 toString 方法。
 */
public record LlmResult(
    /** AI 生成的文本内容 */
    String content,
    /** 使用的模型名称 */
    String modelName,
    /** 提示词消耗的 token 数量 */
    Integer promptTokens,
    /** AI 生成内容消耗的 token 数量 */
    Integer completionTokens,
    /** 总共消耗的 token 数量 */
    Integer totalTokens
) {

    /**
     * 简化构造函数（无 token 统计）
     *
     * @param content   AI 生成的文本内容
     * @param modelName 使用的模型名称
     */
    public LlmResult(String content, String modelName) {
        this(content, modelName, null, null, null);
    }
}
