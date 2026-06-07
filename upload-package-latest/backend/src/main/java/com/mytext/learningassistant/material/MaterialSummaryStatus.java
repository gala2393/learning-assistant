package com.mytext.learningassistant.material;

/**
 * 学习资料摘要生成状态枚举。
 *
 * 控制后台是否已为该资料生成 AI 摘要（由 RAG 模块负责）。
 */
public enum MaterialSummaryStatus {

    /** 等待生成摘要 -- 资料已解析完成，但摘要任务还未开始 */
    PENDING,

    /** 摘要生成成功 */
    SUCCESS,

    /** 摘要生成失败 */
    FAILED
}
