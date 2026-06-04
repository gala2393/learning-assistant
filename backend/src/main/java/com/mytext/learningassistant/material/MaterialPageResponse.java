package com.mytext.learningassistant.material;

import java.util.List;

/**
 * 学习资料页面信息响应记录。
 *
 * 描述一份学习资料中某个页面的预览信息，包含页面尺寸、渲染图文件名
 * 以及该页面关联的知识片段 ID 列表。前端利用这些信息构建"按页阅读"视图。
 *
 * @param pageNo       页码（从 1 开始）
 * @param width        页面宽度（PDF 坐标单位，通常为 1/72 英寸）
 * @param height       页面高度
 * @param imageName    页面渲染图的文件名（如 "page-3.png"）
 * @param chunkIds     该页面包含的知识片段 ID 列表
 * @param renderStatus 渲染状态（"READY" 已渲染 / "PENDING" 等待渲染）
 */
public record MaterialPageResponse(
    Integer pageNo,
    Float width,
    Float height,
    String imageName,
    List<Long> chunkIds,
    String renderStatus
) {
}
