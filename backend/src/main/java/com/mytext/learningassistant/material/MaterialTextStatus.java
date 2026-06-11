package com.mytext.learningassistant.material;

/**
 * 资料文本层抽取状态。
 */
public enum MaterialTextStatus {
    /** 等待后台任务处理。 */
    PENDING,
    /** 后台正在抽取文本。 */
    RUNNING,
    /** 已有部分文本可用于阅读和问答，后台继续补齐。 */
    PARTIAL,
    /** 文本抽取完成。 */
    READY,
    /** 文本抽取失败。 */
    FAILED
}
