package com.mytext.learningassistant.rag;

/**
 * 智能问答临时资料附件。
 * <p>
 * 仅跟随问答历史保存，不进入资料管理。
 */
public record ChatTemporaryMaterial(
    String id,
    String title,
    String originalName,
    String sourceType,
    String text,
    String excerpt,
    Long fileSize
) {
}
