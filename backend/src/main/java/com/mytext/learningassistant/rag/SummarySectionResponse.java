package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * 通用结构化摘要区块，不假设资料一定是学习材料。
 */
public record SummarySectionResponse(
    String title,
    List<String> items,
    List<SummarySourceResponse> sources
) {
}
