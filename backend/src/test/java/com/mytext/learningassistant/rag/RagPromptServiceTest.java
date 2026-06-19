package com.mytext.learningassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

import org.junit.jupiter.api.Test;

class RagPromptServiceTest {

    private final RagPromptService service = new RagPromptService();

    @Test
    void decorateAnswerDoesNotAppendInlineEvidenceWhenStructuredSourcesExist() {
        LearningMaterialEntity material = new LearningMaterialEntity();
        material.setId(1L);
        material.setTitle("Student Handbook");

        MaterialChunkEntity chunk = new MaterialChunkEntity();
        chunk.setId(10L);
        chunk.setPageNo(2);
        chunk.setChunkText("Part 1 Rules");

        ScoredChunk selectedChunk = new ScoredChunk(material, chunk, 0.92, List.of());
        ChatRequest request = new ChatRequest(
            "List all parts in this handbook.",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );

        String answer = service.decorateAnswer(
            request,
            "This handbook contains five parts.",
            List.of(selectedChunk),
            new EvidenceStatus(false, 1.0, 1.0, List.of("parts")),
            new RagPromptService.PromptTextTools(
                text -> text,
                text -> text,
                scored -> scored.chunk().getChunkText(),
                text -> text,
                (currentMaterial, currentChunk) -> "Page " + currentChunk.getPageNo(),
                question -> false,
                text -> text,
                question -> "fallback"
            )
        );

        assertThat(answer).isEqualTo("This handbook contains five parts.");
    }

    @Test
    void decorateAnswerReturnsNoEvidenceMessageWhenEvidenceIsWeak() {
        LearningMaterialEntity material = new LearningMaterialEntity();
        material.setId(1L);
        material.setTitle("Student Handbook");

        MaterialChunkEntity chunk = new MaterialChunkEntity();
        chunk.setId(10L);
        chunk.setPageNo(2);
        chunk.setChunkText("Part 1 Rules");

        ScoredChunk selectedChunk = new ScoredChunk(material, chunk, 0.12, List.of());
        ChatRequest request = new ChatRequest(
            "Explain quantum tunneling.",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );

        String answer = service.decorateAnswer(
            request,
            "Quantum tunneling is a quantum effect.",
            List.of(selectedChunk),
            new EvidenceStatus(true, 0.12, 0.0, List.of("quantum", "tunneling")),
            new RagPromptService.PromptTextTools(
                text -> text,
                text -> text,
                scored -> scored.chunk().getChunkText(),
                text -> text,
                (currentMaterial, currentChunk) -> "Page " + currentChunk.getPageNo(),
                question -> false,
                text -> text,
                question -> "fallback"
            )
        );

        assertThat(answer)
            .contains("当前资料里没有检索到足够依据")
            .contains("Explain quantum tunneling.");
    }
}
