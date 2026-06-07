package com.mytext.learningassistant.rag;

/**
 * RAG 来源响应记录 —— 表示回答中引用的一个资料来源。
 *
 * <p>前端展示"资料依据"时使用此结构，用户可以看到每个来源的资料标题、
 * 页码和文本摘要，并可跳转到原始资料查看完整上下文。</p>
 *
 * @param materialId    来源资料 ID
 * @param chunkId       来源 chunk ID
 * @param materialTitle 来源资料标题
 * @param pageNo        来源页码
 * @param excerpt       来源文本摘要
 * @param score         相关性评分
 */
public record RagSourceResponse(
    Long materialId,
    Long chunkId,
    String materialTitle,
    Integer pageNo,
    String excerpt,
    Double score
) {
}
