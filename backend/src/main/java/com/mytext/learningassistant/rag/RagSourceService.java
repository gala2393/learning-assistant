package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Comparator;
import java.util.LinkedHashSet;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

import org.springframework.stereotype.Service;

/**
 * RAG 回答来源服务。
 *
 * <p>负责来源去重、展示分计算、响应 DTO 组装和来源落库。文本清洗与检索文本构造暂时由
 * RagService 通过回调传入，避免第一阶段拆分时同时迁移大量 prompt/检索工具方法。</p>
 */
@Service
class RagSourceService {

    private final RagQuestionSourceRepository ragQuestionSourceRepository;
    private final RagRetrievalDebugService retrievalDebugService;

    RagSourceService(
        RagQuestionSourceRepository ragQuestionSourceRepository,
        RagRetrievalDebugService retrievalDebugService
    ) {
        this.ragQuestionSourceRepository = ragQuestionSourceRepository;
        this.retrievalDebugService = retrievalDebugService;
    }

    List<RagQuestionSourceEntity> saveSources(
        long questionId,
        List<ScoredChunk> chunks,
        SourceEvidence evidence,
        SourceTextTools textTools
    ) {
        List<RagQuestionSourceEntity> saved = new ArrayList<>();
        for (ScoredChunk chunk : deduplicateSourceChunks(chunks, textTools)) {
            double score = presentScore(displayScore(chunk, evidence, textTools));
            retrievalDebugService.markSelected(
                chunk.material(),
                chunk.chunk(),
                score,
                "最终进入回答来源列表并落库"
            );
            RagQuestionSourceEntity entity = new RagQuestionSourceEntity();
            entity.setQuestionId(questionId);
            entity.setMaterialId(chunk.material().getId());
            entity.setChunkId(chunk.chunk().getId());
            entity.setSourceTitle(chunk.material().getTitle());
            entity.setPageNo(chunk.chunk().getPageNo());
            entity.setExcerpt(textTools.excerpt().apply(chunk));
            entity.setRankScore(score);
            entity.setCreatedAt(LocalDateTime.now());
            saved.add(ragQuestionSourceRepository.save(entity));
        }
        return saved;
    }

    RagSourceResponse toSourceResponse(RagQuestionSourceEntity source) {
        return new RagSourceResponse(
            source.getMaterialId(),
            source.getChunkId(),
            source.getSourceTitle(),
            source.getPageNo(),
            source.getExcerpt(),
            presentScore(source.getRankScore())
        );
    }

    List<ScoredChunk> groundSourcesToAnswer(
        String answer,
        List<ScoredChunk> selectedChunks,
        String question,
        SourceTextTools textTools
    ) {
        if (selectedChunks == null || selectedChunks.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = textTools.significantQueryTerms().apply(question);
        List<String> answerTerms = significantAnswerTerms(answer, textTools);
        String requestedStructureLabel = requestedStructureLabel(question, textTools);
        boolean pureContentsQuestion = isPureContentsQuestion(question, textTools, requestedStructureLabel);
        boolean readerScopedQuestion = isReaderScopedQuestion(question, textTools);
        boolean structureDetailQuestion = hasStructureDetailIntent(question, textTools);
        List<ScoredChunk> grounded = selectedChunks.stream()
            .map(chunk -> {
                String text = textTools.retrievalText().apply(chunk.chunk());
                double answerCoverage = textTools.queryTermCoverage().apply(text, answerTerms);
                double queryCoverage = textTools.queryTermCoverage().apply(text, queryTerms);
                boolean contentsChunk = looksLikeContentsChunk(chunk, textTools);
                boolean requestedStructureMatched = matchesRequestedStructure(chunk, requestedStructureLabel, textTools);
                boolean structuredBodyMarker = containsStructuredBodyMarker(text);
                boolean answerMarkerMatched = containsAnyMarkerTerm(text, answerTerms);
                boolean structureLocatorChunk = looksLikeStructureLocatorChunk(
                    chunk,
                    text,
                    requestedStructureLabel,
                    textTools
                );
                double groundedScore = clampScore(chunk.score()) * 0.45 + answerCoverage * 0.40 + queryCoverage * 0.15;
                if (contentsChunk && pureContentsQuestion) {
                    groundedScore = Math.max(groundedScore, 0.86 + Math.min(0.10, clampScore(chunk.score()) * 0.10));
                }
                if (pureContentsQuestion && !contentsChunk && structuredBodyMarker && answerCoverage >= 0.30) {
                    groundedScore = Math.max(
                        groundedScore,
                        0.89 + Math.min(0.07, answerCoverage * 0.08 + queryCoverage * 0.04)
                    );
                }
                if (pureContentsQuestion && !contentsChunk && answerMarkerMatched) {
                    groundedScore = Math.max(
                        groundedScore,
                        0.965 + Math.min(0.02, clampScore(chunk.score()) * 0.02)
                    );
                }
                if (!contentsChunk && queryCoverage >= 0.8) {
                    groundedScore = Math.max(groundedScore, 0.78 + Math.min(0.14, answerCoverage * 0.14));
                }
                if (answerCoverage >= 0.45 && !contentsChunk) {
                    groundedScore = Math.max(groundedScore, 0.88 + Math.min(0.08, answerCoverage * 0.08));
                }
                if (requestedStructureMatched && !contentsChunk) {
                    groundedScore = Math.max(groundedScore, 0.80 + Math.min(0.10, answerCoverage * 0.08 + queryCoverage * 0.06));
                }
                if (requestedStructureMatched && structuredBodyMarker && !contentsChunk) {
                    groundedScore = Math.max(groundedScore, 0.84 + Math.min(0.08, answerCoverage * 0.08 + queryCoverage * 0.06));
                }
                if (answerMarkerMatched && structuredBodyMarker && !contentsChunk) {
                    groundedScore = Math.max(groundedScore, 0.84 + Math.min(0.08, answerCoverage * 0.08 + queryCoverage * 0.06));
                }
                if (structureDetailQuestion && structuredBodyMarker && !contentsChunk) {
                    groundedScore = Math.max(groundedScore, 0.82 + Math.min(0.08, answerCoverage * 0.08 + queryCoverage * 0.06));
                }
                if (!pureContentsQuestion && structureLocatorChunk && !(structureDetailQuestion && structuredBodyMarker)) {
                    groundedScore = Math.min(
                        groundedScore,
                        answerCoverage >= 0.30 ? 0.74 + Math.min(0.06, answerCoverage * 0.08) : 0.58
                    );
                }
                if (!contentsChunk && !structureLocatorChunk && requestedStructureMatched && answerCoverage >= 0.25) {
                    groundedScore = Math.max(groundedScore, 0.84 + Math.min(0.08, answerCoverage * 0.10));
                }
                if (contentsChunk && !pureContentsQuestion) {
                    groundedScore *= answerCoverage >= 0.8 ? 0.88 : 0.72;
                }
                if (requestedStructureLabel != null && contentsChunk) {
                    groundedScore = Math.min(groundedScore, pureContentsQuestion ? groundedScore : 0.56);
                }
                if (requestedStructureLabel != null
                    && !requestedStructureMatched
                    && !contentsChunk
                    && queryCoverage < 0.20
                    && answerCoverage < 0.20) {
                    groundedScore = Math.min(groundedScore, 0.40);
                }
                if (!contentsChunk
                    && !pureContentsQuestion
                    && !requestedStructureMatched
                    && queryCoverage <= 0.01
                    && answerCoverage < 0.20) {
                    groundedScore = Math.min(groundedScore, 0.36);
                }
                if (!readerScopedQuestion && looksLikeReaderScopedChunk(chunk) && queryCoverage < 0.45) {
                    groundedScore = Math.min(groundedScore * 0.52, 0.35 + queryCoverage * 0.25);
                }
                return chunk.withHighlightTerms(mergeHighlightTerms(chunk.highlightTerms(), answerTerms, queryTerms, text))
                    .withDebug(chunk.route(), chunk.rawScore(), chunk.rerankScore(), groundedScore, chunk.penaltyReason());
            })
            .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
            .toList();
        return grounded.stream()
            .limit(5)
            .toList();
    }

    List<RagSourceResponse> toSourceResponses(
        List<ScoredChunk> chunks,
        SourceEvidence evidence,
        SourceTextTools textTools
    ) {
        return deduplicateSourceChunks(chunks, textTools).stream()
            .map(chunk -> toSourceResponse(chunk, evidence, textTools))
            .toList();
    }

    RagSourceResponse toSourceResponse(
        ScoredChunk chunk,
        SourceEvidence evidence,
        SourceTextTools textTools
    ) {
        MaterialChunkEntity materialChunk = chunk.chunk();
        LearningMaterialEntity material = chunk.material();
        double score = presentScore(displayScore(chunk, evidence, textTools));
        retrievalDebugService.markSelected(
            material,
            materialChunk,
            score,
            "最终进入回答来源响应"
        );
        return new RagSourceResponse(
            material.getId(),
            materialChunk.getId(),
            material.getTitle(),
            materialChunk.getPageNo(),
            textTools.excerpt().apply(chunk),
            score
        );
    }

    List<ScoredChunk> deduplicateSourceChunks(List<ScoredChunk> chunks, SourceTextTools textTools) {
        List<ScoredChunk> deduplicated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ScoredChunk chunk : chunks) {
            if (deduplicated.size() >= 5) {
                break;
            }
            String key = sourceDedupKey(chunk, textTools);
            if (seen.add(key)) {
                deduplicated.add(chunk);
            }
        }
        return deduplicated;
    }

    private String sourceDedupKey(ScoredChunk chunk, SourceTextTools textTools) {
        String excerptKey = textTools.normalize().apply(textTools.excerpt().apply(chunk));
        if (excerptKey.length() > 80) {
            excerptKey = excerptKey.substring(0, 80);
        }
        return chunk.material().getId()
            + ":"
            + Optional.ofNullable(chunk.chunk().getPageNo()).map(String::valueOf).orElse("unknown")
            + ":"
            + excerptKey;
    }

    private double displayScore(ScoredChunk chunk, SourceEvidence evidence, SourceTextTools textTools) {
        double score = clampScore(chunk.score());
        if (!evidence.blocksMaterialAnswer()) {
            return score;
        }
        double termCoverage = textTools.queryTermCoverage().apply(textTools.retrievalText().apply(chunk.chunk()), evidence.queryTerms());
        double capped = Math.min(score, 0.30 + termCoverage * 0.28);
        return Math.max(0.18, Math.min(0.58, capped));
    }

    private boolean isTableOfContentsQuestion(String question, SourceTextTools textTools) {
        String normalized = textTools.normalize().apply(question);
        return normalized.contains("目录")
            || normalized.contains("contents")
            || normalized.contains("toc")
            || normalized.contains("章节")
            || normalized.contains("部分")
            || normalized.contains("chapter")
            || normalized.contains("section")
            || normalized.contains("part");
    }

    private boolean looksLikeContentsChunk(ScoredChunk chunk, SourceTextTools textTools) {
        String retrievalText = textTools.normalize().apply(textTools.retrievalText().apply(chunk.chunk()));
        String sectionTitle = textTools.normalize().apply(chunk.chunk().getSectionTitle());
        String route = chunk.route() == null ? "" : chunk.route().toUpperCase(Locale.ROOT);
        return route.contains("STRUCTURE_TOC")
            || route.contains("STRUCTURE_HEADING")
            || retrievalText.contains("目录")
            || retrievalText.contains("contents")
            || retrievalText.contains("toc")
            || sectionTitle.contains("目录")
            || sectionTitle.contains("contents");
    }

    private boolean isReaderScopedQuestion(String question, SourceTextTools textTools) {
        String normalized = textTools.normalize().apply(question);
        return normalized.contains("当前页")
            || normalized.contains("本页")
            || normalized.contains("这一页")
            || normalized.contains("这页")
            || normalized.contains("当前阅读")
            || normalized.contains("currentpage")
            || normalized.contains("thispage");
    }

    private boolean looksLikeReaderScopedChunk(ScoredChunk chunk) {
        String route = chunk.route() == null ? "" : chunk.route().toUpperCase(Locale.ROOT);
        return route.contains("CURRENT_PAGE") || route.contains("CURRENT_CHUNK");
    }

    /**
     * 目录题只在用户明确要求“列目录/列章节/列部分”时触发。
     * 仅仅出现 chapter/section/part 不足以说明用户在问目录，
     * 否则 “Chapter 2 讲了什么” 这类正文问题会被误判成目录问题。
     */
    private boolean isExplicitContentsQuestion(String question, SourceTextTools textTools) {
        String normalized = textTools.normalize().apply(question);
        boolean explicitContents = normalized.contains("鐩綍")
            || normalized.contains("contents")
            || normalized.contains("toc")
            || normalized.contains("目录");
        if (explicitContents) {
            return true;
        }
        boolean structureWord = normalized.contains("绔犺妭")
            || normalized.contains("閮ㄥ垎")
            || normalized.contains("chapter")
            || normalized.contains("section")
            || normalized.contains("part")
            || normalized.contains("章节")
            || normalized.contains("部分");
        boolean listingIntent = normalized.contains("鍝簺")
            || normalized.contains("鏈夊摢浜?")
            || normalized.contains("鍒楀嚭")
            || normalized.contains("姒傝")
            || normalized.contains("overview")
            || normalized.contains("list")
            || normalized.contains("all")
            || normalized.contains("哪些")
            || normalized.contains("列出")
            || normalized.contains("概览")
            || normalized.contains("所有")
            || normalized.contains("全部");
        return structureWord && listingIntent;
    }

    private boolean isPureContentsQuestion(String question, SourceTextTools textTools, String requestedStructureLabel) {
        return isExplicitContentsQuestion(question, textTools)
            && requestedStructureLabel == null
            && !hasStructureDetailIntent(question, textTools);
    }

    private boolean hasStructureDetailIntent(String question, SourceTextTools textTools) {
        String normalized = textTools.normalize().apply(question);
        return normalized.contains("\u4e3b\u8981\u8bb2\u4ec0\u4e48")
            || normalized.contains("\u4e3b\u8981\u5185\u5bb9")
            || normalized.contains("\u5177\u4f53\u5185\u5bb9")
            || normalized.contains("\u8be6\u7ec6\u5185\u5bb9")
            || normalized.contains("\u8be6\u7ec6\u8bf4\u660e")
            || normalized.contains("\u5177\u4f53\u8bf4\u660e")
            || normalized.contains("\u5c55\u5f00\u8bb2")
            || normalized.contains("\u5c55\u5f00\u8bf4")
            || normalized.contains("\u6982\u62ec")
            || normalized.contains("\u603b\u7ed3")
            || normalized.contains("\u5f52\u7eb3")
            || normalized.contains("\u8bf4\u660e")
            || normalized.contains("\u4ecb\u7ecd")
            || normalized.contains("\u8865\u5145")
            || normalized.contains("\u5305\u62ec")
            || normalized.contains("\u5305\u542b")
            || normalized.contains("\u6db5\u76d6")
            || normalized.contains("\u8bb2\u4e86\u4ec0\u4e48")
            || normalized.contains("\u8bf4\u4e86\u4ec0\u4e48")
            || normalized.contains("\u662f\u4ec0\u4e48\u5185\u5bb9")
            || normalized.contains("whatitcovers")
            || normalized.contains("covers")
            || normalized.contains("explain")
            || normalized.contains("summarize")
            || normalized.contains("summary")
            || normalized.contains("details")
            || normalized.contains("detail")
            || normalized.contains("include")
            || normalized.contains("including");
    }

    private String requestedStructureLabel(String question, SourceTextTools textTools) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher chinese = Pattern.compile("\u7b2c\\s*([一二三四五六七八九十百千零两0-9]+)\\s*(条|章|节|部分)").matcher(question);
        if (chinese.find()) {
            return textTools.normalize().apply("\u7b2c" + chinese.group(1).trim() + chinese.group(2));
        }
        Matcher english = Pattern.compile("(?i)\\b(article|chapter|part|section)\\s*(\\d{1,3})\\b").matcher(question);
        if (english.find()) {
            return textTools.normalize().apply(english.group(1).toLowerCase(Locale.ROOT) + english.group(2));
        }
        return null;
    }

    private boolean matchesRequestedStructure(ScoredChunk chunk, String requestedStructureLabel, SourceTextTools textTools) {
        if (requestedStructureLabel == null || requestedStructureLabel.isBlank()) {
            return false;
        }
        String sectionTitle = textTools.normalize().apply(chunk.chunk().getSectionTitle());
        String text = textTools.normalize().apply(textTools.retrievalText().apply(chunk.chunk()));
        return sectionTitle.contains(requestedStructureLabel) || text.contains(requestedStructureLabel);
    }

    private boolean looksLikeStructureLocatorChunk(
        ScoredChunk chunk,
        String retrievalText,
        String requestedStructureLabel,
        SourceTextTools textTools
    ) {
        if (requestedStructureLabel == null || requestedStructureLabel.isBlank()) {
            return false;
        }
        String normalizedText = textTools.normalize().apply(retrievalText);
        String normalizedSectionTitle = textTools.normalize().apply(chunk.chunk().getSectionTitle());
        if (!normalizedText.contains(requestedStructureLabel) && !normalizedSectionTitle.contains(requestedStructureLabel)) {
            return false;
        }
        String compactText = normalizedText.replace(requestedStructureLabel, "");
        String compactSectionTitle = normalizedSectionTitle.replace(requestedStructureLabel, "");
        boolean veryShortText = compactText.length() <= 36;
        boolean titleOnly = !normalizedSectionTitle.isBlank() && normalizedText.equals(normalizedSectionTitle);
        boolean headingRoute = chunk.route() != null && chunk.route().toUpperCase(Locale.ROOT).contains("STRUCTURE_HEADING");
        boolean containsStructuredBodyMarker = containsStructuredBodyMarker(retrievalText);
        boolean substantialBodyText = compactText.length() >= 60 || containsStructuredBodyMarker;
        return veryShortText
            || titleOnly
            || headingRoute
            || (!substantialBodyText && compactSectionTitle.length() <= 18);
    }

    private boolean containsStructuredBodyMarker(String text) {
        return Pattern.compile("\\b[A-Z][A-Z0-9_]{3,}\\b").matcher(text == null ? "" : text).find();
    }

    private boolean containsAnyMarkerTerm(String text, List<String> terms) {
        if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
            return false;
        }
        String lowered = text.toLowerCase(Locale.ROOT);
        return terms.stream()
            .filter(term -> term != null && !term.isBlank() && term.contains("_"))
            .map(term -> term.toLowerCase(Locale.ROOT))
            .anyMatch(lowered::contains);
    }

    private List<String> extractStructuredMarkers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> markers = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\b[A-Z][A-Z0-9_]{3,}\\b").matcher(text);
        while (matcher.find()) {
            markers.add(matcher.group().toLowerCase(Locale.ROOT));
            if (markers.size() >= 8) {
                break;
            }
        }
        return markers;
    }

    private List<String> mergeHighlightTerms(List<String> existingTerms, List<String> answerTerms, List<String> queryTerms, String text) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        appendHighlightTerms(terms, queryTerms);
        appendHighlightTerms(terms, answerTerms);
        appendHighlightTerms(terms, extractStructuredMarkers(text));
        appendHighlightTerms(terms, existingTerms);
        return terms.stream().limit(16).toList();
    }

    private void appendHighlightTerms(Set<String> target, List<String> terms) {
        if (terms == null) {
            return;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank()) {
                target.add(term);
            }
        }
    }

    private List<String> significantAnswerTerms(String text, SourceTextTools textTools) {
        List<String> terms = new ArrayList<>();
        Matcher marker = Pattern.compile("\\b[A-Z][A-Z0-9_]{3,}\\b").matcher(text == null ? "" : text);
        while (marker.find()) {
            String exactMarker = marker.group().toLowerCase(Locale.ROOT);
            if (exactMarker.length() >= 4) {
                terms.add(exactMarker);
            }
            String normalized = textTools.normalize().apply(marker.group());
            if (normalized.length() >= 4) {
                terms.add(normalized);
            }
        }
        terms.addAll(textTools.significantQueryTerms().apply(text).stream().limit(8).toList());
        return terms.stream().distinct().limit(12).toList();
    }

    private double clampScore(Double score) {
        if (score == null || Double.isNaN(score) || Double.isInfinite(score)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, score));
    }

    private double presentScore(Double score) {
        return Math.round(clampScore(score) * 1000.0) / 1000.0;
    }

    record SourceEvidence(boolean blocksMaterialAnswer, List<String> queryTerms) {
    }

    record SourceTextTools(
        Function<String, String> normalize,
        Function<MaterialChunkEntity, String> retrievalText,
        Function<ScoredChunk, String> excerpt,
        Function<String, List<String>> significantQueryTerms,
        BiFunction<String, List<String>, Double> queryTermCoverage
    ) {
    }
}
