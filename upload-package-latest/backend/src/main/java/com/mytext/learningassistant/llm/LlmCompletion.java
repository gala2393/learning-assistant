package com.mytext.learningassistant.llm;

/**
 * LLM 完成结果记录类
 *
 * 用于存储大语言模型对话完成后的结果信息，包括：
 * - AI 生成的文本内容
 * - 使用的模型名称
 * - Token 使用统计（提示词 token 数、生成 token 数、总 token 数）
 * - 是否使用了自定义模型
 *
 * 使用 Java record 特性，自动生成 getter、equals、hashCode 和 toString 方法。
 */
public record LlmCompletion(
    /** AI 生成的文本内容 */
    String content,
    /** 使用的模型名称，如 "gpt-4"、"deepseek-chat" 等 */
    String modelName,
    /** 提示词消耗的 token 数量 */
    Integer promptTokens,
    /** AI 生成内容消耗的 token 数量 */
    Integer completionTokens,
    /** 总共消耗的 token 数量 */
    Integer totalTokens,
    /** 是否使用了用户自定义配置的模型 */
    boolean customModel
) {

    /**
     * 简化构造函数（无 token 统计，非自定义模型）
     *
     * @param content   AI 生成的文本内容
     * @param modelName 使用的模型名称
     */
    public LlmCompletion(String content, String modelName) {
        this(content, modelName, null, null, null, false);
    }

    /**
     * 标准构造函数（有 token 统计，非自定义模型）
     *
     * @param content          AI 生成的文本内容
     * @param modelName        使用的模型名称
     * @param promptTokens     提示词消耗的 token 数量
     * @param completionTokens AI 生成内容消耗的 token 数量
     * @param totalTokens      总共消耗的 token 数量
     */
    public LlmCompletion(String content, String modelName, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        this(content, modelName, promptTokens, completionTokens, totalTokens, false);
    }
}
