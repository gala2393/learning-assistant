package com.mytext.learningassistant.llm;

/**
 * 用户 LLM 配置连通性测试的响应体（Response DTO）。
 *
 * <p>职责：
 * <ul>
 *   <li>在用户保存或修改自定义 LLM 配置后，返回连通性测试结果。</li>
 *   <li>告诉前端测试是否成功、提示消息、以及实际使用的模型名称。</li>
 * </ul>
 *
 * @param ok      测试是否成功（true 表示成功连通 LLM 服务并收到回复）
 * @param message 测试结果的提示消息，成功时为成功提示，失败时为错误原因
 * @param model   测试时实际使用的模型名称
 */
public record UserLlmTestResponse(
    /** 测试是否成功 */
    boolean ok,
    /** 测试结果的提示消息 */
    String message,
    /** 测试时实际使用的模型名称 */
    String model
) {
}
