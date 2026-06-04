package com.mytext.learningassistant.material;

/**
 * 学习资料详情响应记录。
 *
 * 与 {@link MaterialResponse} 结构相同，语义上用于单条资料的详情接口。
 * 包含资料的完整元数据以及解析、预览、摘要状态。
 *
 * @param id                   资料主键 ID
 * @param title                资料标题
 * @param sourceType           来源类型
 * @param originalName         原始文件名
 * @param sourceUrl            来源 URL
 * @param fileSize             文件大小（字节）
 * @param parseStatus          解析状态
 * @param parseProgressPercent 解析进度百分比（0 ~ 100）
 * @param parseStage           当前解析阶段描述
 * @param parseMessage         解析阶段详细说明
 * @param summaryStatus        摘要生成状态
 * @param previewStatus        预览生成状态
 * @param previewError         预览失败的错误信息
 * @param pageCount            文档总页数
 * @param chunkCount           知识片段总数
 * @param createdAt            创建时间
 * @param updatedAt            最后更新时间
 */
public record MaterialDetailResponse(
    Long id,
    String title,
    String sourceType,
    String originalName,
    String sourceUrl,
    Long fileSize,
    String parseStatus,
    Integer parseProgressPercent,
    String parseStage,
    String parseMessage,
    String summaryStatus,
    String previewStatus,
    String previewError,
    Integer pageCount,
    Integer chunkCount,
    String createdAt,
    String updatedAt
) {
}
