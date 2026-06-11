package com.mytext.learningassistant.admin;

/**
 * 管理员提交向量索引重建后的响应。
 *
 * @param submitted  已提交到后台线程池的资料数量
 * @param materialId 指定资料 ID；为 null 表示批量处理全部已解析资料
 * @param message    可直接展示给管理员的处理说明
 */
public record AdminVectorIndexRebuildResponse(
    int submitted,
    Long materialId,
    String message
) {
}
