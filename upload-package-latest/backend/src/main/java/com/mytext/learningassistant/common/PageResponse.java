package com.mytext.learningassistant.common;

import java.util.List;

/**
 * 分页响应 — 当列表数据太多时，不一次性返回所有，而是按页返回。
 * <p>
 * 示例：请求"第1页，每页10条"，返回 items=[前10条数据], page=1, size=10, total=53。
 * 前端可以用 total/size 计算总页数，生成分页导航。
 *
 * @param <T>   列表项的类型
 * @param items 当前页的数据列表
 * @param page  当前页码（从1开始）
 * @param size  每页条数
 * @param total 总数据条数（不是总页数）
 */
public record PageResponse<T>(
    List<T> items,
    int page,
    int size,
    long total
) {
}
