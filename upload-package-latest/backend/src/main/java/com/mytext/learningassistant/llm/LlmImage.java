package com.mytext.learningassistant.llm;

/**
 * LLM 图片记录类
 *
 * 用于存储用户上传的图片数据，支持多模态 LLM 对话。
 * 使用 Java record 特性，自动生成 getter、equals、hashCode 和 toString 方法。
 */
public record LlmImage(
    /** 图片的 Base64 编码数据 */
    String base64Data,
    /** 图片的媒体类型（MIME type），如 "image/jpeg"、"image/png" 等 */
    String mediaType
) {
}
