package com.mytext.learningassistant.rag;

/**
 * 用户反馈响应记录 —— 表示一条反馈记录的详情。
 *
 * @param id         反馈记录 ID
 * @param questionId 关联的问答 ID
 * @param rating     评分（1 好评，-1 差评）
 * @param comment    文字评论
 * @param updatedAt  最后更新时间
 */
public record RagFeedbackResponse(
    Long id,
    Long questionId,
    Integer rating,
    String comment,
    String updatedAt
) {
}
