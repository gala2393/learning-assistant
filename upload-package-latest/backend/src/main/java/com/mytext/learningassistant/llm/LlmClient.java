package com.mytext.learningassistant.llm;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * LLM 客户端接口
 *
 * 定义了与大语言模型（LLM）交互的标准接口。
 * 提供同步和流式两种对话方式，支持文本和图片输入。
 */
public interface LlmClient {

    /**
     * 同步对话方法（支持图片输入）
     *
     * @param systemPrompt 系统提示词，用于设定 AI 的行为和角色
     * @param userPrompt   用户输入的文本内容
     * @param images       用户上传的图片列表，可以为空
     * @return LlmResult 对象的 Optional 包装，如果对话成功则包含结果，否则为空
     */
    Optional<LlmResult> chat(String systemPrompt, String userPrompt, List<LlmImage> images);

    /**
     * 同步对话方法（仅文本输入，无图片）
     *
     * @param systemPrompt 系统提示词，用于设定 AI 的行为和角色
     * @param userPrompt   用户输入的文本内容
     * @return LlmResult 对象的 Optional 包装，如果对话成功则包含结果，否则为空
     */
    default Optional<LlmResult> chat(String systemPrompt, String userPrompt) {
        // 默认实现：调用带图片参数的方法，传入空图片列表
        return chat(systemPrompt, userPrompt, List.of());
    }

    /**
     * 流式对话方法（支持图片输入）
     *
     * @param systemPrompt 系统提示词，用于设定 AI 的行为和角色
     * @param userPrompt   用户输入的文本内容
     * @param images       用户上传的图片列表，可以为空
     * @param onChunk      接收流式数据块的回调函数，每收到一个数据块都会被调用
     * @return 完整的对话结果字符串
     */
    String chatStream(String systemPrompt, String userPrompt, List<LlmImage> images, Consumer<String> onChunk);
}
