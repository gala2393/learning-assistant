package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * RAG 学习资料摘要的响应体。
 * <p>
 * 封装了一次摘要的完整信息，包括摘要文本、来源资料、使用的模型等。
 *
 * @param summaryId    摘要记录的唯一 ID
 * @param materialId   摘要对应的学习资料 ID
 * @param materialTitle 学习资料的标题
 * @param summary      摘要正文内容
 * @param summaryType  摘要类型，如 "AUTO"（系统自动生成）
 * @param modelName    生成摘要所使用的大语言模型名称
 * @param sourceCount  摘要基于的资料片段总数
 * @param createdAt    摘要创建时间，格式为 "yyyy-MM-dd HH:mm:ss"
 */
public record RagSummaryResponse(
    Long summaryId,
    Long materialId,
    String materialTitle,
    String summary,
    String summaryType,
    String modelName,
    Integer sourceCount,
    String createdAt,
    List<SummarySectionResponse> sections,
    List<SummarySourceResponse> sources,
    String userNote
) {
}
