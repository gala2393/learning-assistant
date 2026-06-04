package com.mytext.learningassistant.admin;

/**
 * 管理后台 — 资料状态修改请求。
 * 管理员可以修改资料的解析状态和摘要状态（如强制标记为成功或失败）。
 */
public record AdminMaterialStatusRequest(
    String parseStatus,     // 新的解析状态（可选，如 SUCCESS/FAILED）
    String summaryStatus    // 新的摘要状态（可选，如 SUCCESS/FAILED）
) {
}
