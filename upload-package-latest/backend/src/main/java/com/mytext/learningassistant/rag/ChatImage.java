package com.mytext.learningassistant.rag;

/**
 * 聊天图片数据记录，表示用户在对话中上传的图片。
 *
 * <p>支持两种图片来源：</p>
 * <ul>
 *   <li>{@code base64Data}：直接传入 Base64 编码的图片数据</li>
 *   <li>{@code dataUrl}：传入 data URI 格式的字符串（如 {@code data:image/png;base64,...}），
 *       系统会自动解析其中的 MIME 类型和 Base64 数据</li>
 * </ul>
 *
 * <p>在 RAG 流程中，图片会作为多模态输入传给 LLM，让模型能够"看到"学习资料中的图表、公式等视觉内容。</p>
 *
 * @param dataUrl     data URI 格式的图片地址（可选，当 base64Data 为空时会从此字段提取数据）
 * @param base64Data  Base64 编码的图片数据（优先使用）
 * @param mediaType   图片的 MIME 类型（如 "image/png"、"image/jpeg"）
 */
public record ChatImage(
    String dataUrl,
    String base64Data,
    String mediaType
) {

    /**
     * 解析并返回图片的实际 MIME 类型。
     *
     * <p>解析逻辑：</p>
     * <ol>
     *   <li>如果 {@code mediaType} 字段已填写，直接返回</li>
     *   <li>否则尝试从 {@code dataUrl} 中提取（格式为 {@code data:<mediaType>;base64,...}）</li>
     *   <li>如果都无法解析，返回空字符串</li>
     * </ol>
     *
     * @return 图片的 MIME 类型字符串，如 "image/png"
     */
    public String resolvedMediaType() {
        // 优先使用显式指定的 mediaType
        if (mediaType != null && !mediaType.isBlank()) {
            return mediaType;
        }
        // 尝试从 dataUrl 中解析 MIME 类型
        if (dataUrl == null || !dataUrl.startsWith("data:")) {
            return "";
        }
        // dataUrl 格式: "data:image/png;base64,xxxxx"
        // 找到第一个分号的位置，提取 "data:" 和 ";" 之间的部分作为 MIME 类型
        int semicolonIndex = dataUrl.indexOf(';');
        if (semicolonIndex <= 5) {
            return "";
        }
        return dataUrl.substring(5, semicolonIndex);
    }
}
