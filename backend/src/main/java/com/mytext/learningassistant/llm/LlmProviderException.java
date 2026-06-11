package com.mytext.learningassistant.llm;

/**
 * LLM 供应商调用异常。
 * <p>
 * 这个异常用于区分“模型服务不可用、超时、输出过长被供应商截断”等真实失败和普通空回答。
 * 流式接口会把异常转换为 SSE error 事件，让前端显示明确提示，而不是把失败伪装成本地兜底回答。
 */
public class LlmProviderException extends RuntimeException {

    public LlmProviderException(String message) {
        super(message);
    }

    public LlmProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
