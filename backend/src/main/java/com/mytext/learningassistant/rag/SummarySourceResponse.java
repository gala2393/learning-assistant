package com.mytext.learningassistant.rag;

/**
 * 摘要依据来源，可由前端跳转到阅读器中的具体片段。
 */
public record SummarySourceResponse(
    Long materialId,
    Long chunkId,
    String title,
    Integer pageNo,
    Integer chunkIndex,
    String excerpt
) {
}
