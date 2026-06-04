package com.mytext.learningassistant.material;

/**
 * 学习资料解析状态枚举。
 *
 * 表示一份学习资料在上传后，后台文本提取和分块处理的当前阶段。
 * 状态流转通常为：PENDING -> PARSING -> SUCCESS / FAILED。
 */
public enum MaterialParseStatus {

    /** 等待解析 -- 文件已保存，后台任务尚未启动 */
    PENDING,

    /** 正在解析 -- 后台正在提取文本、生成分块和向量索引 */
    PARSING,

    /** 解析成功 -- 文本提取和索引构建全部完成，资料可用于阅读和问答 */
    SUCCESS,

    /** 解析失败 -- 文本提取或索引构建过程中发生错误 */
    FAILED
}
