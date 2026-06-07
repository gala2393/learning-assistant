package com.mytext.learningassistant.rag;

/**
 * 用户反馈请求记录 —— 表示用户对某条问答结果提交的评价。
 *
 * @param rating  评分：1 表示好评，-1 表示差评
 * @param comment 文字评论（可选）
 */
public record RagFeedbackRequest(
    Integer rating,
    String comment
) {
}
