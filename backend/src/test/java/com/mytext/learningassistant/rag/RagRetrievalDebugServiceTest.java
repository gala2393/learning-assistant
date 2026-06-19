package com.mytext.learningassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

import org.junit.jupiter.api.Test;

class RagRetrievalDebugServiceTest {

    private final RagRetrievalDebugService service = new RagRetrievalDebugService();

    @Test
    void mergesRoutesScoresAndSelectionReasonForSameChunk() {
        LearningMaterialEntity material = material(1L, "Debug Material");
        MaterialChunkEntity chunk = chunk(100L, 12, 3, "JOIN connects rows from multiple tables.");

        try (RagRetrievalDebugService.RetrievalDebugSession ignored =
                 service.begin("What does JOIN do?", material.getId(), false)) {
            service.recordCandidate("BM25", material, chunk, 0.41, null, 0.41, "命中关键词 JOIN", null);
            service.recordCandidate("VECTOR", material, chunk, 0.77, null, 0.77, "命中语义相似片段", "目录页降权");
            service.recordCandidate("RERANK", material, chunk, 0.77, 0.93, 0.93, "重排后正文优先", null);
            service.markSelected(material, chunk, 0.93, "最终进入回答来源");

            List<RetrievalDebugEntry> snapshot = service.snapshot();

            assertThat(snapshot).hasSize(1);
            RetrievalDebugEntry entry = snapshot.get(0);
            assertThat(entry.routes()).containsExactlyInAnyOrder("BM25", "VECTOR", "RERANK");
            assertThat(entry.rawScore()).isEqualTo(0.77);
            assertThat(entry.rerankScore()).isEqualTo(0.93);
            assertThat(entry.finalScore()).isEqualTo(0.93);
            assertThat(entry.selected()).isTrue();
            assertThat(entry.reason()).contains("命中关键词 JOIN");
            assertThat(entry.reason()).contains("命中语义相似片段");
            assertThat(entry.selectedReason()).contains("最终进入回答来源");
            assertThat(entry.penaltyReason()).contains("目录页降权");
        }
    }

    @Test
    void closingSessionClearsThreadLocalSnapshot() {
        LearningMaterialEntity material = material(2L, "Cleanup Material");
        MaterialChunkEntity chunk = chunk(200L, 1, 1, "marker");

        RagRetrievalDebugService.RetrievalDebugSession session = service.begin("marker?", material.getId(), false);
        service.recordCandidate("BM25", material, chunk, 0.5, null, 0.5, "命中 marker", null);
        assertThat(service.snapshot()).hasSize(1);

        session.close();

        assertThat(service.snapshot()).isEmpty();
    }

    private LearningMaterialEntity material(Long id, String title) {
        LearningMaterialEntity material = new LearningMaterialEntity();
        material.setId(id);
        material.setTitle(title);
        return material;
    }

    private MaterialChunkEntity chunk(Long id, Integer pageNo, Integer chunkIndex, String chunkText) {
        MaterialChunkEntity chunk = new MaterialChunkEntity();
        chunk.setId(id);
        chunk.setPageNo(pageNo);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkText(chunkText);
        return chunk;
    }
}
