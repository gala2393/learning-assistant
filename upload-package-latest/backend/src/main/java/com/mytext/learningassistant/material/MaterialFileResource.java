package com.mytext.learningassistant.material;

import java.nio.file.Path;

/**
 * 学习资料文件资源记录。
 *
 * 封装从磁盘读取资料文件所需的信息，用于构造 HTTP 文件下载 / 在线预览响应。
 *
 * @param path          文件在磁盘上的绝对路径
 * @param fileName      建议的文件名（用于 Content-Disposition 头）
 * @param contentType   MIME 类型（如 "application/pdf"）
 * @param contentLength 文件大小（字节）
 */
public record MaterialFileResource(
    Path path,
    String fileName,
    String contentType,
    long contentLength
) {
}
