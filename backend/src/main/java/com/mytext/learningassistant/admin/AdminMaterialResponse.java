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
    String uploadStatus,        // 原文件上传状态（UPLOADING/UPLOADED/FAILED）
    String textStatus,          // 文本抽取状态（PENDING/RUNNING/PARTIAL/READY/FAILED）
    String indexStatus,         // 检索索引状态（PENDING/RUNNING/PARTIAL/READY/FAILED）
    String ocrStatus,           // OCR 处理状态（DISABLED/PENDING/RUNNING/PARTIAL/READY/FAILED）
    Integer processingProgressPercent, // 后台流水线综合进度百分比 (0-100)
    String processingStage,     // 后台流水线当前阶段
    String processingMessage,   // 后台流水线当前说明
    Integer indexedChunkCount,  // 已进入索引的片段数量
    Integer textPageCount,      // 已完成文本抽取/OCR 的页数
    Integer pageCount,          // 资料总页数
    String summaryStatus,       // 摘要生成状态
    Integer chunkCount,         // 文本分块数量
    String createdAt,           // 上传时间
    String updatedAt            // 最后更新时间
) {
}
