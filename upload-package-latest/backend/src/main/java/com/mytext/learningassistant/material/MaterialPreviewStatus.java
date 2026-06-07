package com.mytext.learningassistant.material;

/**
 * 学习资料预览状态枚举。
 *
 * 表示学习资料是否已生成可供前端展示的阅读预览（例如 PDF 页面渲染图）。
 */
public enum MaterialPreviewStatus {

    /** 无预览 -- 资料类型不支持预览，或尚未处理 */
    NONE,

    /** 预览就绪 -- 已成功生成预览文件，前端可直接渲染 */
    READY,

    /** 降级预览 -- 预览生成工具不可用（如缺少 LibreOffice），回退到纯文本模式 */
    DEGRADED,

    /** 预览失败 -- 预览文件生成或读取过程中发生错误 */
    FAILED
}
