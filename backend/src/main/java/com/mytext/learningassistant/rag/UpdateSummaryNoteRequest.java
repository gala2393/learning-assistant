package com.mytext.learningassistant.rag;

/**
 * 更新用户整理版摘要。
 *
 * @param userNote 用户自行整理后的文本，可为空字符串
 */
public record UpdateSummaryNoteRequest(String userNote) {
}
