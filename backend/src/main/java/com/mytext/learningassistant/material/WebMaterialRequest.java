package com.mytext.learningassistant.material;

import jakarta.validation.constraints.NotBlank;

public record WebMaterialRequest(
    String title,
    @NotBlank(message = "来源链接不能为空")
    String sourceUrl
) {
}
