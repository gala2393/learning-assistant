package com.mytext.learningassistant.llm;

import java.util.List;

public record UserLlmConfigResponse(
    boolean enabled,
    String baseUrl,
    String model,
    boolean hasApiKey,
    String activeLabel,
    Long activeConfigId,
    List<UserLlmConfigItemResponse> configs
) {
}
