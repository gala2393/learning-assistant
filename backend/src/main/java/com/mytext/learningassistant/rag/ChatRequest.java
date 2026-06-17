package com.mytext.learningassistant.rag;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

/**
 * RAG 聊天请求记录，封装用户发起一次问答所需的参数。
 *
 * <p>这是 RAG 问答流程的入口数据结构，包含问题内容、上下文信息、
 * 对话历史、图片附件等，供 {@link RagService#chat} 和 {@link RagService#chatStream} 使用。</p>
 *
 * @param question            用户提出的问题；超过 {@link #MAX_QUESTION_CHARS} 字会在入站时截断
 * @param materialId          指定的学习资料 ID；指定后仅在该资料范围内检索
 * @param mode                问答模式："MATERIAL" 表示资料问答，"GENERAL" 表示通用问答
 * @param chunkId             当前正在阅读的 chunk ID，用于上下文定位
 * @param currentPageNo       当前正在阅读的页码，用于获取同页上下文
 * @param currentPageChunkIds 当前页面包含的 chunk ID 列表，用于精确定位当前页内容
 * @param selectedText        用户选中的原文文本，选中后基于选中内容回答
 * @param images              用户上传的图片列表，用于多模态问答
 * @param temporaryMaterial   用户上传的临时资料附件
 * @param answerStyle         回答风格："STUDY" 学习模式、"HOMEWORK" 作业模式
 * @param history             对话历史列表，用于多轮对话上下文
 * @param conversationId      会话 ID，传入后新消息会归入同一会话
 */
public record ChatRequest(
    @NotBlank(message = "问题不能为空")
    String question,
    Long materialId,
    String mode,
    Long chunkId,
    Integer currentPageNo,
    List<Long> currentPageChunkIds,
    String selectedText,
    List<ChatImage> images,
    ChatTemporaryMaterial temporaryMaterial,
    String answerStyle,
    List<ChatMessage> history,
    Long conversationId
) {
    /** 直接提问的真实可用上限；超过部分在请求入站时直接舍弃，避免接口返回 400。 */
    public static final int MAX_QUESTION_CHARS = 6000;

    public ChatRequest {
        if (question != null && question.length() > MAX_QUESTION_CHARS) {
            question = question.substring(0, MAX_QUESTION_CHARS);
        }
    }
}
