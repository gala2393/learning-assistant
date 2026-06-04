package com.mytext.learningassistant.material;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 创建分片上传会话的请求记录。
 *
 * 大文件上传采用分片方式：前端先调用此接口创建会话，获取 sessionId，
 * 然后逐个调用分片上传接口将文件内容发送到后端。
 *
 * @param clientUploadId  客户端生成的唯一上传标识（用于幂等去重，同一文件重复请求不会创建多个会话）
 * @param title           资料标题
 * @param originalName    原始文件名（含扩展名）
 * @param sourceType      来源类型（可选，为空时根据文件扩展名自动推断）
 * @param sourceUrl       来源 URL（可选）
 * @param fileSize        文件总大小（字节），必须大于 0
 * @param chunkSize       每个分片的大小（字节），至少为 1
 * @param checksumSha256  整个文件的 SHA-256 校验值（可选，用于上传完成后校验完整性）
 */
public record MaterialUploadSessionCreateRequest(
    @NotBlank String clientUploadId,
    @NotBlank String title,
    @NotBlank String originalName,
    String sourceType,
    String sourceUrl,
    @NotNull @Positive Long fileSize,
    @NotNull @Min(1) Integer chunkSize,
    @Size(max = 128) String checksumSha256
) {
}
