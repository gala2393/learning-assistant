package com.mytext.learningassistant.admin;

/**
 * 系统依赖健康检查响应。
 *
 * @param name    依赖名称
 * @param enabled 配置层面是否启用
 * @param healthy 当前运行环境是否可用
 * @param message 检查结果说明
 */
public record SystemDependencyResponse(
    String name,
    boolean enabled,
    boolean healthy,
    String message
) {
}
