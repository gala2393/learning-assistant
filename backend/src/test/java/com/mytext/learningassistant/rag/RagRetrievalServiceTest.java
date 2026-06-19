package com.mytext.learningassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

import org.junit.jupiter.api.Test;

class RagRetrievalServiceTest {

    private final RagRetrievalService service = new RagRetrievalService(null, null, null, new RagRetrievalDebugService());

    @Test
    void selectTopChunksDoesNotStopWhenLaterChunksUseNormalizedScores() {
        LearningMaterialEntity material = material(1L, "Normalized Score Material");
        List<ScoredChunk> selected = service.selectTopChunks(
            List.of(
                scoredChunk(material, 101L, 1, 0, 1.42),
                scoredChunk(material, 102L, 2, 1, 0.93),
                scoredChunk(material, 103L, 3, 2, 0.88),
                scoredChunk(material, 104L, 4, 3, 0.81)
            ),
            4
        );

        assertThat(selected)
            .extracting(chunk -> chunk.chunk().getId())
            .containsExactly(101L, 102L, 103L, 104L);
    }

    private LearningMaterialEntity material(Long id, String title) {
        LearningMaterialEntity material = new LearningMaterialEntity();
        material.setId(id);
        material.setTitle(title);
        return material;
    }

    private ScoredChunk scoredChunk(
        LearningMaterialEntity material,
        Long chunkId,
        Integer pageNo,
        Integer chunkIndex,
        double score
    ) {
        MaterialChunkEntity chunk = new MaterialChunkEntity();
        chunk.setId(chunkId);
        chunk.setMaterialId(material.getId());
        chunk.setPageNo(pageNo);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkText("chunk-" + chunkId);
        return new ScoredChunk(material, chunk, score, List.of("marker"));
    }
}
