package com.mytext.learningassistant.material;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record MaterialUploadSessionCreateRequest(
    @NotBlank String clientUploadId,
    @NotBlank String title,
    @NotBlank String originalName,
    String sourceType,
    String sourceUrl,
    @NotNull @Positive Long fileSize,
    @NotNull @Min(1) Integer chunkSize,
    @Size(max = 128) String checksumSha256
) {
}
