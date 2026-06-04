package com.mytext.learningassistant.admin;

/**
 * 管理后台 — 资料响应 DTO。
 * 在管理员查看资料列表时使用，包含资料的所有关键信息和上传者用户名。
 */
public record AdminMaterialResponse(
    Long id,                    // 资料 ID
    Long ownerId,               // 上传者用户 ID
    String ownerUsername,       // 上传者用户名（关联查询后填充）
    String title,               // 资料标题
    String sourceType,          // 文件类型（PDF/DOCX/PPTX/TXT/MD/HTML）
    String originalName,        // 原始文件名
    String sourceUrl,           // 来源 URL（网页导入时）
    Long fileSize,              // 文件大小（字节）
    String parseStatus,         // 解析状态（PENDING/PARSING/SUCCESS/FAILED）
    Integer parseProgressPercent, // 解析进度百分比 (0-100)
    String parseStage,          // 当前解析阶段描述
    String parseMessage,        // 解析阶段附加信息
    String summaryStatus,       // 摘要生成状态
    Integer chunkCount,         // 文本分块数量
    String createdAt,           // 上传时间
    String updatedAt            // 最后更新时间
) {
}
