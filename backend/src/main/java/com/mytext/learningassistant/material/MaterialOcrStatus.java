package com.mytext.learningassistant.material;

/**
 * OCR 处理状态。
 */
public enum MaterialOcrStatus {
    /** 未启用 OCR 或资料不需要 OCR。 */
    DISABLED,
    /** 等待 OCR。 */
    PENDING,
    /** OCR 正在运行。 */
    RUNNING,
    /** 部分页 OCR 完成。 */
    PARTIAL,
    /** OCR 已完成。 */
    READY,
    /** OCR 失败。 */
    FAILED
}
