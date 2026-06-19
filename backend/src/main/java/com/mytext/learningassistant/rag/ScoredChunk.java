package com.mytext.learningassistant.rag;

import java.util.List;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

/**
 * 带分数的资料片段，绑定资料实体、片段实体和检索相关分数。
 *
 * <p>这个类型在 RAG 检索、来源展示和调试链路之间传递。保持为包内可见，避免控制器或外部模块依赖内部检索细节。</p>
 */
record ScoredChunk(
    LearningMaterialEntity material,
    MaterialChunkEntity chunk,
    double score,
    List<String> highlightTerms,
    String route,
    Double rawScore,
    Double rerankScore,
    String penaltyReason
) {
    ScoredChunk(LearningMaterialEntity material, MaterialChunkEntity chunk, double score) {
        this(material, chunk, score, List.of());
    }

    ScoredChunk(LearningMaterialEntity material, MaterialChunkEntity chunk, double score, List<String> highlightTerms) {
        this(material, chunk, score, highlightTerms, null, score, null, null);
    }

    ScoredChunk withHighlightTerms(List<String> nextHighlightTerms) {
        return new ScoredChunk(
            material,
            chunk,
            score,
            nextHighlightTerms == null ? List.of() : nextHighlightTerms,
            route,
            rawScore,
            rerankScore,
            penaltyReason
        );
    }

    ScoredChunk withScore(double nextScore) {
        return new ScoredChunk(material, chunk, nextScore, highlightTerms, route, rawScore, rerankScore, penaltyReason);
    }

    ScoredChunk withDebug(String route, Double rawScore, Double rerankScore, Double finalScore, String penaltyReason) {
        return new ScoredChunk(
            material,
            chunk,
            finalScore == null ? score : finalScore,
            highlightTerms,
            route == null || route.isBlank() ? this.route : route,
            rawScore == null ? this.rawScore : rawScore,
            rerankScore == null ? this.rerankScore : rerankScore,
            penaltyReason == null || penaltyReason.isBlank() ? this.penaltyReason : penaltyReason
        );
    }
}
