package com.mytext.learningassistant.material;

/**
 * 知识片段（Chunk）响应记录。
 *
 * 表示从学习资料中拆分出的单个知识片段，用于前端列表展示和检索结果展示。
 * 每个片段包含原始文本、所在页码、章节标题、层次路径等上下文信息。
 *
 * @param id             片段主键 ID
 * @param materialId     所属资料的 ID
 * @param chunkIndex     片段序号（从 1 开始，前端显示用）
 * @param chunkText      片段的完整文本内容
 * @param pageNo         片段所在页码（PDF 等分页文档有值）
 * @param sectionTitle   片段所在章节标题
 * @param hierarchyPath  层次路径（如 "资料标题 > 第3页 > 切片5"）
 * @param summary        片段摘要（取文本前 180 字符左右）
 * @param keywords       片段关键词（逗号分隔，最多 8 个）
 * @param excerpt        片段摘录（截取前 160 字符，用于列表快速预览）
 * @param createdAt      创建时间（格式化字符串）
 */
public record MaterialChunkResponse(
    Long id,
    Long materialId,
    Integer chunkIndex,
    String chunkText,
    Integer pageNo,
    String sectionTitle,
    String hierarchyPath,
    String summary,
    String keywords,
    String excerpt,
    String createdAt
) {
}
