package com.mytext.learningassistant.material;

import jakarta.validation.constraints.Size;

public record UpdateMaterialRequest(
    @Size(max = 128)
    String title,

    @Size(max = 512)
    String sourceUrl
) {
}
