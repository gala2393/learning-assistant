package com.mytext.learningassistant.material;

/**
 * 临时资料解析结果。
 *
 * <p>临时资料只作为当前智能问答的上下文使用，不会写入资料管理列表。</p>
 */
public record TemporaryMaterialResponse(
    String id,
    String title,
    String originalName,
    String sourceType,
    String text,
    String excerpt,
    Long fileSize,
    Boolean contextStored
) {
}
