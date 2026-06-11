package com.mytext.learningassistant.material;

/**
 * 资料后台任务执行状态。
 */
public enum MaterialProcessingJobStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    RETRY_WAIT,
    CANCELLED
}
