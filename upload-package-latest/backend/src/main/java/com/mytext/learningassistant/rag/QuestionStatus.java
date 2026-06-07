package com.mytext.learningassistant.rag;

/**
 * 问答状态枚举 —— 表示一条 RAG 问答记录的处理状态。
 *
 * <p>状态流转：INIT -> RUNNING -> SUCCESS / FAILED</p>
 *
 * <ul>
 *   <li>{@link #INIT}    —— 初始状态，问答刚创建</li>
 *   <li>{@link #RUNNING} —— 正在处理中（正在检索、生成回答等）</li>
 *   <li>{@link #SUCCESS} —— 处理成功，已生成回答</li>
 *   <li>{@link #FAILED}  —— 处理失败（如 LLM 调用异常、检索出错等）</li>
 * </ul>
 */
public enum QuestionStatus {
    /** 初始状态，问答刚创建 */
    INIT,
    /** 正在处理中 */
    RUNNING,
    /** 处理成功 */
    SUCCESS,
    /** 处理失败 */
    FAILED
}
