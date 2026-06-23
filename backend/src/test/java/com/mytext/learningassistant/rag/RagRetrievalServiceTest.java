package com.mytext.learningassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

import org.junit.jupiter.api.Test;

class RagRetrievalServiceTest {

    private final RagRetrievalService service = new RagRetrievalService(null, null, null, new RagRetrievalDebugService());
    private final RagQueryIntentService queryIntentService = new RagQueryIntentService();

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

    @Test
    void semanticCandidateGetsTermCoverageAfterColloquialQueryCleanup() {
        LearningMaterialEntity material = material(1L, "Keyword Index Material");
        ScoredChunk vectorChunk = scoredChunk(
            material,
            201L,
            1,
            0,
            0.82,
            "关键词索引：保留资料中的核心术语，辅助 BM25 检索和语义检索融合。"
        );

        List<ScoredChunk> fused = service.fuseAndRerankChunks(
            "关键词索引呢",
            List.of(vectorChunk),
            List.of(),
            List.of(),
            retrievalTextTools()
        );

        assertThat(fused).hasSize(1);
        assertThat(fused.get(0).score()).isGreaterThan(0.5);
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
        return scoredChunk(material, chunkId, pageNo, chunkIndex, score, "chunk-" + chunkId);
    }

    private ScoredChunk scoredChunk(
        LearningMaterialEntity material,
        Long chunkId,
        Integer pageNo,
        Integer chunkIndex,
        double score,
        String text
    ) {
        MaterialChunkEntity chunk = new MaterialChunkEntity();
        chunk.setId(chunkId);
        chunk.setMaterialId(material.getId());
        chunk.setPageNo(pageNo);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkText(text);
        return new ScoredChunk(material, chunk, score, List.of("marker"));
    }

    private RagRetrievalService.RetrievalTextTools retrievalTextTools() {
        return new RagRetrievalService.RetrievalTextTools(
            MaterialChunkEntity::getChunkText,
            queryIntentService::significantQueryTerms,
            queryIntentService::queryTermCoverage,
            question -> false,
            text -> false
        );
    }
}
