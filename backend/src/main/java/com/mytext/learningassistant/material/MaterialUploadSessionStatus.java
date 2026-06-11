package com.mytext.learningassistant.material;

/**
 * 分片上传会话状态枚举。
 *
 * 跟踪大文件分片上传的生命周期。
 * 状态流转：UPLOADING -> PROCESSING -> SUCCESS / FAILED。
 */
public enum MaterialUploadSessionStatus {

    /** 正在上传 -- 前端正在逐个分片上传文件内容 */
    UPLOADING,

    /** 处理中 -- 所有分片已到达，后端正在合并文件并校验内容 */
    PROCESSING,

    /** 上传成功 -- 原始文件已保存，解析与索引任务已进入后台队列 */
    SUCCESS,

    /** 上传失败 -- 分片接收、合并或校验过程中出错 */
    FAILED
}
