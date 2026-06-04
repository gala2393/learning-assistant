package com.mytext.learningassistant.material;

import jakarta.validation.constraints.Size;

/**
 * 更新学习资料请求记录。
 *
 * 用于修改已有学习资料的标题或来源 URL，不涉及文件重新上传。
 * 所有字段均为可选，仅非空字段会被更新。
 *
 * @param title     新标题，最长 128 字符；为 null 时不修改
 * @param sourceUrl 新来源 URL，最长 512 字符；为 null 时不修改
 */
public record UpdateMaterialRequest(
    @Size(max = 128)
    String title,

    @Size(max = 512)
    String sourceUrl
) {
}
