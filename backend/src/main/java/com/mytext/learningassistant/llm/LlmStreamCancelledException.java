package com.mytext.learningassistant.llm;

/**
 * LLM 流式输出被调用方主动取消。
 *
 * <p>典型场景是浏览器端点击“暂停输出”后关闭 SSE 连接。这个异常不是模型供应商失败，
 * 也不应该触发本地兜底回答或继续落库；它只负责把“客户端已不再接收 token”的信号
 * 从回调层一路传回 Controller。</p>
 */
public class LlmStreamCancelledException extends RuntimeException {

    public LlmStreamCancelledException(String message, Throwable cause) {
        super(message, cause);
    }
}
