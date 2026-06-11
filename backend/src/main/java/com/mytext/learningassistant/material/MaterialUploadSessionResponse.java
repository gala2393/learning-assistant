package com.mytext.learningassistant.material;

import java.util.List;

/**
 * 分片上传会话响应记录。
 *
 * 返回上传会话的当前状态和进度信息，前端根据此响应判断是否继续上传分片
 * 以及显示解析进度。
 *
 * @param sessionId            后端生成的会话唯一标识
 * @param clientUploadId       客户端上传标识（用于幂等匹配）
 * @param materialId           关联的学习资料 ID
 * @param title                资料标题
 * @param originalName         原始文件名
 * @param sourceType           来源类型
 * @param sourceUrl            来源 URL
 * @param fileSize             文件总大小（字节）
 * @param chunkSize            每个分片大小（字节）
 * @param totalChunks          总分片数
 * @param uploadedChunks       已上传分片数
 * @param uploadedChunkIndexes 已上传分片索引，前端用于精确断点续传
 * @param status               会话状态（UPLOADING / PROCESSING / SUCCESS / FAILED）
 * @param errorMessage         失败时的错误信息
 * @param parseProgressPercent 解析进度百分比（0 ~ 100）
 * @param parseStage           当前解析阶段描述
 * @param parseMessage         解析阶段详细说明
 * @param createdAt            会话创建时间
 * @param updatedAt            会话最后更新时间
 */
public record MaterialUploadSessionResponse(
    String sessionId,
    String clientUploadId,
    Long materialId,
    String title,
    String originalName,
    String sourceType,
    String sourceUrl,
    Long fileSize,
    Integer chunkSize,
    Integer totalChunks,
    Integer uploadedChunks,
    List<Integer> uploadedChunkIndexes,
    String status,
    String errorMessage,
    Integer parseProgressPercent,
    String parseStage,
    String parseMessage,
    String uploadStatus,
    String textStatus,
    String indexStatus,
    String ocrStatus,
    Integer processingProgressPercent,
    String processingStage,
    String processingMessage,
    Integer indexedChunkCount,
    Integer textPageCount,
    String createdAt,
    String updatedAt
) {
}
