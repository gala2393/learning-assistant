package com.mytext.learningassistant.material;

/**
 * 文件下载凭据响应记录。
 *
 * 当前端需要在不带 Cookie 的场景下（如 <iframe>、<img> 标签）下载资料文件时，
 * 先调用接口获取一个有时效的一次性 ticket，然后拼接到文件 URL 中完成鉴权。
 *
 * @param ticket    随机生成的一次性令牌
 * @param url       拼接好 ticket 参数的文件下载完整 URL
 * @param expiresAt 过期时间戳（毫秒，Unix Epoch）
 */
public record MaterialFileTicketResponse(
    String ticket,
    String url,
    long expiresAt
) {
}
