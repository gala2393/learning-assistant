package com.mytext.learningassistant.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.LearningMaterialRepository;
import com.mytext.learningassistant.material.MaterialChunkEntity;
import com.mytext.learningassistant.material.MaterialChunkRepository;
import com.mytext.learningassistant.material.MaterialTextStatus;
import com.mytext.learningassistant.rerank.RerankCandidate;
import com.mytext.learningassistant.rerank.RerankedCandidate;
import com.mytext.learningassistant.rerank.RerankerClient;

import org.springframework.stereotype.Service;

/**
 * RAG 检索排序服务。
 *
 * <p>当前先承接“多路候选融合 + rerank + 检索调试记录”这段核心逻辑，
 * 后续再逐步把召回、结构化检索、当前页检索迁移进来。这样可以先把最容易膨胀的
 * 排序职责从 RagService 中拆出来，同时保持现有接口和用户行为不变。</p>
 */
@Service
class RagRetrievalService {

    private static final int RETRIEVAL_CACHE_MAX_ENTRIES = 128;

    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final RerankerClient rerankerClient;
    private final RagRetrievalDebugService retrievalDebugService;
    private final ConcurrentMap<String, RetrievalCacheEntry> retrievalResultCache = new ConcurrentHashMap<>();

    RagRetrievalService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        RerankerClient rerankerClient,
        RagRetrievalDebugService retrievalDebugService
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.rerankerClient = rerankerClient;
        this.retrievalDebugService = retrievalDebugService;
    }

    List<LearningMaterialEntity> retrievalScopeMaterials(long userId, Long materialId) {
        return materialId == null
            ? learningMaterialRepository.findByOwnerIdOrderByCreatedAtDesc(userId).stream()
                .filter(this::isMaterialReadyForRetrieval)
                .toList()
            : learningMaterialRepository.findByIdAndOwnerId(materialId, userId).stream()
                .filter(this::isMaterialReadyForRetrieval)
                .toList();
    }

    /**
     * 只让 READY/PARTIAL 的资料进入检索。
     *
     * <p>PARTIAL 代表资料已经有可用正文或部分索引，后台 OCR/向量增强可能仍在补齐。
     * 这里允许 PARTIAL，是为了让大 PDF 在轻量解析完成后即可开始问答，而不是等待全部后台任务结束。</p>
     */
    boolean isMaterialReadyForRetrieval(LearningMaterialEntity material) {
        if (material == null) {
            return false;
        }
        MaterialTextStatus textStatus = material.getTextStatus();
        return textStatus == MaterialTextStatus.READY || textStatus == MaterialTextStatus.PARTIAL;
    }

    /**
     * 读取一次完整检索结果缓存。
     *
     * <p>缓存只保存 chunkId 和分数，不缓存实体对象；命中后会重新查库并校验资料仍属于当前用户、
     * 资料仍可检索。任一条件失效就丢弃缓存，避免用户删除资料或重新解析后拿到旧来源。</p>
     */
    List<ScoredChunk> cachedRetrievalChunks(String cacheKey, long userId) {
        RetrievalCacheEntry cached = retrievalResultCache.get(cacheKey);
        if (cached == null) {
            return null;
        }
        List<ScoredChunk> chunks = new ArrayList<>();
        for (CachedScoredChunk cachedChunk : cached.chunks()) {
            MaterialChunkEntity chunk = materialChunkRepository.findById(cachedChunk.chunkId()).orElse(null);
            if (chunk == null) {
                retrievalResultCache.remove(cacheKey);
                return null;
            }
            LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(chunk.getMaterialId(), userId).orElse(null);
            if (material == null || !isMaterialReadyForRetrieval(material)) {
                retrievalResultCache.remove(cacheKey);
                return null;
            }
            chunks.add(new ScoredChunk(material, chunk, cachedChunk.score(), cachedChunk.highlightTerms()));
        }
        return chunks;
    }

    /**
     * 记住一次检索结果，供同一用户、同一资料集合、同一问题的连续请求复用。
     *
     * <p>这里使用简单容量上限和整表清空策略，因为缓存只优化短时间内的继续生成/重复请求，
     * 不是业务正确性依赖；超过上限后清空比维护复杂 LRU 更符合当前使用场景。</p>
     */
    void rememberRetrievalResult(String cacheKey, List<ScoredChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        if (retrievalResultCache.size() >= RETRIEVAL_CACHE_MAX_ENTRIES) {
            retrievalResultCache.clear();
        }
        retrievalResultCache.put(cacheKey, new RetrievalCacheEntry(
            chunks.stream()
                .map(chunk -> new CachedScoredChunk(chunk.chunk().getId(), chunk.score(), List.copyOf(chunk.highlightTerms())))
                .toList()
        ));
    }

    /**
     * 执行 RAG 的主检索路径：查询扩展、向量召回、BM25 召回、摘要种子召回、HyDE 召回和最终融合。
     *
     * <p>RagService 仍负责具体召回函数和文本工具函数，本服务只编排候选集合的合并与排序，
     * 这样可以把大块排序逻辑集中在一个可测试的类里。</p>
     */
    List<ScoredChunk> selectVectorOrKeywordChunks(
        long userId,
        String question,
        Long materialId,
        RetrievalCallbacks callbacks,
        RetrievalTextTools tools
    ) {
        List<LearningMaterialEntity> materials = retrievalScopeMaterials(userId, materialId);
        // 缓存键包含资料更新时间和 chunk 数量；资料重新解析或新增切片后会自然失效。
        String cacheKey = retrievalCacheKey(userId, materialId, question, materials, callbacks.normalize(), callbacks.hash());
        List<ScoredChunk> cachedChunks = cachedRetrievalChunks(cacheKey, userId);
        if (cachedChunks != null) {
            recordDebugCandidates("CACHE", cachedChunks, "命中检索结果缓存", null);
            return cachedChunks;
        }

        List<ScoredChunk> vectorChunks = new ArrayList<>();
        List<ScoredChunk> bm25Chunks = new ArrayList<>();
        List<ScoredChunk> summarySeedChunks = callbacks.summarySeedChunks().apply(question);
        List<String> queries = callbacks.expandedQueries().apply(question);
        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            // 原始问题权重最高，扩展查询稍降权，避免扩展词把主题拉偏。
            double weight = i == 0 ? 1.0 : 0.82;
            vectorChunks.addAll(weightedChunks(callbacks.vectorChunks().apply(query), weight));
            bm25Chunks.addAll(weightedChunks(callbacks.bm25Chunks().apply(query), weight));
        }
        callbacks.hydeQuery().get().ifPresent(hydeQuery ->
            vectorChunks.addAll(weightedChunks(callbacks.vectorChunks().apply(hydeQuery), callbacks.hydeWeight().get()))
        );

        List<ScoredChunk> selected = selectTopChunks(
            fuseAndRerankChunks(question, vectorChunks, bm25Chunks, summarySeedChunks, tools),
            callbacks.topK().get()
        );
        recordDebugCandidates("HYBRID_SELECTED", selected, "混合检索与重排后进入候选上下文", null);
        rememberRetrievalResult(cacheKey, selected);
        return selected;
    }

    /**
     * 将向量、BM25 和摘要种子三路候选融合后交给 reranker 精排。
     *
     * @param question          用户检索问题
     * @param vectorChunks      向量召回候选
     * @param bm25Chunks        BM25 召回候选
     * @param summarySeedChunks 摘要种子候选
     * @param tools             检索文本工具函数
     * @return 融合并精排后的候选片段
     */
    List<ScoredChunk> fuseAndRerankChunks(
        String question,
        List<ScoredChunk> vectorChunks,
        List<ScoredChunk> bm25Chunks,
        List<ScoredChunk> summarySeedChunks,
        RetrievalTextTools tools
    ) {
        Map<Long, HybridCandidate> candidates = new LinkedHashMap<>();
        collectRouteCandidates("VECTOR", vectorChunks, candidates, "向量语义检索召回");
        collectRouteCandidates("BM25", bm25Chunks, candidates, "BM25 关键词检索召回");
        collectRouteCandidates("SUMMARY_SEED", summarySeedChunks, candidates, "资料摘要命中后补充前置片段");
        if (candidates.isEmpty()) {
            return List.of();
        }

        double maxVector = candidates.values().stream().mapToDouble(candidate -> candidate.vectorScore).max().orElse(0.0);
        double maxBm25 = candidates.values().stream().mapToDouble(candidate -> candidate.bm25Score).max().orElse(0.0);
        double maxSummary = candidates.values().stream().mapToDouble(candidate -> candidate.summaryScore).max().orElse(0.0);
        List<String> queryTerms = tools.significantQueryTerms().apply(question);

        List<ScoredChunk> fusedChunks = candidates.values().stream()
            .map(candidate -> fuseCandidate(question, candidate, maxVector, maxBm25, maxSummary, queryTerms, tools))
            .filter(chunk -> chunk.score() > 0.0)
            .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
            .toList();
        return rerankChunks(question, fusedChunks, tools);
    }

    void recordDebugCandidates(String route, List<ScoredChunk> chunks, String reason, String penaltyReason) {
        for (ScoredChunk chunk : chunks) {
            retrievalDebugService.recordCandidate(
                firstNonBlank(chunk.route(), route),
                chunk.material(),
                chunk.chunk(),
                chunk.rawScore() == null ? chunk.score() : chunk.rawScore(),
                chunk.rerankScore(),
                chunk.score(),
                reason,
                firstNonBlank(chunk.penaltyReason(), penaltyReason)
            );
        }
    }

    private void collectRouteCandidates(
        String route,
        List<ScoredChunk> chunks,
        Map<Long, HybridCandidate> candidates,
        String reason
    ) {
        for (ScoredChunk chunk : chunks) {
            HybridCandidate candidate = candidates.computeIfAbsent(chunk.chunk().getId(), ignored -> new HybridCandidate(chunk));
            if ("VECTOR".equals(route)) {
                candidate.vectorScore = Math.max(candidate.vectorScore, chunk.score());
            } else if ("BM25".equals(route)) {
                candidate.bm25Score = Math.max(candidate.bm25Score, chunk.score());
            } else if ("SUMMARY_SEED".equals(route)) {
                candidate.summaryScore = Math.max(candidate.summaryScore, chunk.score());
            }
            if (candidate.highlightTerms.isEmpty()) {
                candidate.highlightTerms = chunk.highlightTerms();
            }
            retrievalDebugService.recordCandidate(route, chunk.material(), chunk.chunk(), chunk.score(), null, null, reason, null);
        }
    }

    private ScoredChunk fuseCandidate(
        String question,
        HybridCandidate candidate,
        double maxVector,
        double maxBm25,
        double maxSummary,
        List<String> queryTerms,
        RetrievalTextTools tools
    ) {
        double vectorScore = maxVector <= 0.0 ? 0.0 : candidate.vectorScore / maxVector;
        double bm25Score = maxBm25 <= 0.0 ? 0.0 : candidate.bm25Score / maxBm25;
        double summaryScore = maxSummary <= 0.0 ? 0.0 : candidate.summaryScore / maxSummary;
        double termScore = tools.queryTermCoverage().apply(tools.retrievalText().apply(candidate.chunk), queryTerms);
        // 权重按当前项目经验值设置：语义召回优先，BM25 保底精确词命中，摘要和关键词覆盖作为辅助信号。
        double fusedScore = (0.48 * vectorScore) + (0.30 * bm25Score) + (0.12 * summaryScore) + (0.10 * termScore);
        String penaltyReason = tools.looksLikeContentsChunk().apply(tools.retrievalText().apply(candidate.chunk))
            && !tools.isTableOfContentsQuestion().apply(question)
            ? "非目录问题命中目录片段，后续正文排序会降权"
            : null;
        ScoredChunk fused = new ScoredChunk(candidate.material, candidate.chunk, fusedScore, candidate.highlightTerms)
            .withDebug("HYBRID_FUSION", candidate.bestRawScore(), null, fusedScore, penaltyReason);
        retrievalDebugService.recordCandidate(
            "HYBRID_FUSION",
            fused.material(),
            fused.chunk(),
            candidate.bestRawScore(),
            null,
            fusedScore,
            "向量、BM25、摘要和关键词覆盖率融合",
            penaltyReason
        );
        return fused;
    }

    private List<ScoredChunk> rerankChunks(String question, List<ScoredChunk> chunks, RetrievalTextTools tools) {
        if (chunks.size() < 2) {
            return chunks;
        }
        List<RerankCandidate> candidates = chunks.stream()
            .map(chunk -> new RerankCandidate(chunk.chunk().getId(), tools.retrievalText().apply(chunk.chunk()), chunk.score()))
            .toList();
        List<RerankedCandidate> rerankedCandidates = rerankerClient.rerank(question, candidates);
        if (rerankedCandidates.isEmpty()) {
            return chunks;
        }

        Map<Long, ScoredChunk> byChunkId = chunks.stream()
            .collect(
                LinkedHashMap::new,
                (map, chunk) -> map.putIfAbsent(chunk.chunk().getId(), chunk),
                LinkedHashMap::putAll
            );
        List<ScoredChunk> reranked = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (RerankedCandidate candidate : rerankedCandidates) {
            ScoredChunk chunk = byChunkId.get(candidate.id());
            if (chunk == null || !seen.add(candidate.id())) {
                continue;
            }
            ScoredChunk rerankedChunk = chunk.withDebug("RERANK", chunk.rawScore(), candidate.score(), candidate.score(), chunk.penaltyReason());
            retrievalDebugService.recordCandidate(
                "RERANK",
                rerankedChunk.material(),
                rerankedChunk.chunk(),
                rerankedChunk.rawScore(),
                candidate.score(),
                candidate.score(),
                "Reranker 精排后调整排序",
                rerankedChunk.penaltyReason()
            );
            reranked.add(rerankedChunk);
        }
        for (ScoredChunk chunk : chunks) {
            if (seen.add(chunk.chunk().getId())) {
                reranked.add(chunk);
            }
        }
        return reranked;
    }

    private String firstNonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private List<ScoredChunk> weightedChunks(List<ScoredChunk> chunks, double weight) {
        if (chunks.isEmpty() || weight == 1.0) {
            return chunks;
        }
        return chunks.stream()
            .map(chunk -> chunk.withScore(chunk.score() * weight))
            .toList();
    }

    private String retrievalCacheKey(
        long userId,
        Long materialId,
        String question,
        List<LearningMaterialEntity> materials,
        Function<String, String> normalize,
        Function<String, String> hash
    ) {
        StringBuilder key = new StringBuilder(materialId == null ? "user:" + userId : "material:" + materialId);
        key.append("|q=").append(hash.apply(normalize.apply(question)));
        for (LearningMaterialEntity material : materials) {
            key.append('|')
                .append(material.getId())
                .append(':')
                .append(material.getChunkCount())
                .append(':')
                .append(material.getUpdatedAt());
        }
        return key.toString();
    }

    List<ScoredChunk> selectTopChunks(List<ScoredChunk> scoredChunks, int topK) {
        List<ScoredChunk> selected = new ArrayList<>();
        Set<String> seenPages = new HashSet<>();
        for (ScoredChunk chunk : scoredChunks) {
            if (chunk.score() <= 0.0) {
                continue;
            }
            if (selected.size() >= topK) {
                break;
            }
            String pageKey = chunk.material().getId() + ":" + (chunk.chunk().getPageNo() == null ? "null" : chunk.chunk().getPageNo());
            // 前 3 个结果允许集中在同页，后续结果尽量跨页，降低上下文被同一页重复片段占满的概率。
            if (selected.size() >= 3 && seenPages.contains(pageKey)) {
                continue;
            }
            selected.add(chunk);
            seenPages.add(pageKey);
        }
        return selected;
    }

    record RetrievalTextTools(
        Function<MaterialChunkEntity, String> retrievalText,
        Function<String, List<String>> significantQueryTerms,
        BiFunction<String, List<String>, Double> queryTermCoverage,
        Function<String, Boolean> isTableOfContentsQuestion,
        Function<String, Boolean> looksLikeContentsChunk
    ) {
    }

    record RetrievalCallbacks(
        Function<String, List<ScoredChunk>> vectorChunks,
        Function<String, List<ScoredChunk>> bm25Chunks,
        Function<String, List<ScoredChunk>> summarySeedChunks,
        Function<String, List<String>> expandedQueries,
        Supplier<java.util.Optional<String>> hydeQuery,
        Supplier<Double> hydeWeight,
        Supplier<Integer> topK,
        Function<String, String> normalize,
        Function<String, String> hash
    ) {
    }

    private static final class HybridCandidate {
        private final LearningMaterialEntity material;
        private final MaterialChunkEntity chunk;
        private double vectorScore;
        private double bm25Score;
        private double summaryScore;
        private List<String> highlightTerms;

        private HybridCandidate(ScoredChunk scoredChunk) {
            this.material = scoredChunk.material();
            this.chunk = scoredChunk.chunk();
            this.highlightTerms = scoredChunk.highlightTerms();
        }

        private double bestRawScore() {
            return Math.max(vectorScore, Math.max(bm25Score, summaryScore));
        }
    }

    private record RetrievalCacheEntry(List<CachedScoredChunk> chunks) {
    }

    private record CachedScoredChunk(Long chunkId, double score, List<String> highlightTerms) {
    }
}
