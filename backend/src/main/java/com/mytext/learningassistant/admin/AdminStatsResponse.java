package com.mytext.learningassistant.admin;

/**
 * 管理后台 — 仪表盘统计数据响应。
 * 展示系统的整体概况：有多少用户、多少资料、多少问答等。
 */
public record AdminStatsResponse(
    long userCount,       // 注册用户总数
    long materialCount,   // 学习资料总数
    long questionCount,   // 问答记录总数
    long favoriteCount,   // 收藏总数
    long logCount         // 系统日志总数
) {
}
