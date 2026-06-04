package com.mytext.learningassistant.material;

import jakarta.validation.constraints.NotBlank;

/**
 * 网页导入学习资料请求记录。
 *
 * 用户通过粘贴 URL 的方式导入网页内容作为学习资料。
 * 后端会抓取该 URL 的内容，提取纯文本并创建资料记录。
 *
 * @param title     资料标题，可选（为空时自动从 URL 推断）
 * @param sourceUrl 网页来源 URL，不能为空
 */
public record WebMaterialRequest(
    String title,
    @NotBlank(message = "来源链接不能为空")
    String sourceUrl
) {
}
