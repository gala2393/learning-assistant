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

    /** 处理中 -- 所有分片已到达，后端正在合并文件并执行文本解析 */
    PROCESSING,

    /** 上传成功 -- 文件合并、解析、索引全部完成 */
    SUCCESS,

    /** 上传失败 -- 合并、校验或解析过程中出错 */
    FAILED
}
