package com.mytext.learningassistant.material;

/**
 * 资料原文件上传状态。
 */
public enum MaterialUploadStatus {
    /** 文件正在上传或合并。 */
    UPLOADING,
    /** 原文件已经完整落盘。 */
    UPLOADED,
    /** 上传或落盘失败。 */
    FAILED
}
