package com.mytext.learningassistant.material;

public record MaterialChunkResponse(
    Long id,
    Long materialId,
    Integer chunkIndex,
    String chunkText,
    Integer pageNo,
    String sectionTitle,
    String excerpt,
    String createdAt
) {
}
