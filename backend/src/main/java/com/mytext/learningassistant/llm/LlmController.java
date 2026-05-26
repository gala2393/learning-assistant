package com.mytext.learningassistant.llm;

import com.mytext.learningassistant.common.ApiResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmProperties properties;

    public LlmController(LlmProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/status")
    public ApiResponse<LlmStatusResponse> status() {
        boolean configured = properties.configured();
        String message = configured
            ? "大模型已配置，智能问答会优先调用第三方模型。"
            : "大模型未配置完整，系统会使用本地 RAG 兜底答案。";
        return ApiResponse.ok(new LlmStatusResponse(
            properties.enabled(),
            configured,
            message
        ));
    }
}
