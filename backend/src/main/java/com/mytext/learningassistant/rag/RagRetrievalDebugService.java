package com.mytext.learningassistant.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

import org.springframework.stereotype.Service;

/**
 * RAG 检索调试信息收集服务。
 *
 * <p>当前阶段先用请求线程内的 ThreadLocal 收集调试信息，避免一次性改动大量私有检索方法签名。
 * 后续拆出独立 RagRetrievalService 时，可以把这里的会话对象替换为显式传递的检索上下文。</p>
 */
@Service
public class RagRetrievalDebugService {

    /** 单次问答最多返回的调试候选数，避免响应体过大。 */
    private static final int MAX_DEBUG_ENTRIES = 40;

    /** 当前线程正在处理的检索调试会话。 */
    private final ThreadLocal<DebugSession> currentSession = new ThreadLocal<>();

    /**
     * 开始一次新的检索调试会话。
     *
     * @param question    用户原始问题
     * @param materialId  当前资料 ID；普通智能问答可能为空
     * @param generalChat 是否为普通智能问答
     */
    public RetrievalDebugSession begin(String question, Long materialId, boolean generalChat) {
        DebugSession debugSession = new DebugSession(question, materialId, generalChat);
        currentSession.set(debugSession);
        return new RetrievalDebugSession(debugSession);
    }

    /** 清理当前线程的调试会话，防止线程复用时串数据。 */
    private void clear() {
        currentSession.remove();
    }

    /**
     * 记录一个候选片段。
     *
     * @param route         召回路径，例如 BM25、VECTOR、STRUCTURE_TOC
     * @param material      候选片段所属资料
     * @param chunk         候选片段
     * @param rawScore      原始召回分
     * @param rerankScore   重排分；没有重排时传 null
     * @param finalScore    最终排序分
     * @param reason        召回原因
     * @param penaltyReason 降权原因；没有降权时传 null
     */
    public void recordCandidate(
        String route,
        LearningMaterialEntity material,
        MaterialChunkEntity chunk,
        Double rawScore,
        Double rerankScore,
        Double finalScore,
        String reason,
        String penaltyReason
    ) {
        DebugSession session = currentSession.get();
        if (session == null || material == null || chunk == null || chunk.getId() == null) {
            return;
        }
        MutableEntry entry = session.entries.computeIfAbsent(
            chunk.getId(),
            ignored -> new MutableEntry(material, chunk)
        );
        entry.query = session.question;
        entry.routes.add(route);
        entry.rawScore = maxNullable(entry.rawScore, rawScore);
        entry.rerankScore = maxNullable(entry.rerankScore, rerankScore);
        entry.finalScore = maxNullable(entry.finalScore, finalScore);
        if (reason != null && !reason.isBlank()) {
            entry.reason = mergeReason(entry.reason, reason);
        }
        if (penaltyReason != null && !penaltyReason.isBlank()) {
            entry.penaltyReason = mergeReason(entry.penaltyReason, penaltyReason);
        }
    }

    /**
     * 标记一个片段最终进入回答来源。
     *
     * @param material   资料实体
     * @param chunk      片段实体
     * @param finalScore 最终展示分
     * @param reason     入选原因
     */
    public void markSelected(
        LearningMaterialEntity material,
        MaterialChunkEntity chunk,
        Double finalScore,
        String reason
    ) {
        DebugSession session = currentSession.get();
        if (session == null || material == null || chunk == null || chunk.getId() == null) {
            return;
        }
        MutableEntry entry = session.entries.computeIfAbsent(
            chunk.getId(),
            ignored -> new MutableEntry(material, chunk)
        );
        entry.query = session.question;
        entry.selected = true;
        entry.finalScore = maxNullable(entry.finalScore, finalScore);
        if (reason != null && !reason.isBlank()) {
            entry.selectedReason = mergeReason(entry.selectedReason, reason);
        }
    }

    /**
     * 获取当前会话的调试快照。
     *
     * @return 已按“最终入选优先、分数倒序、片段顺序”排序的调试条目
     */
    public List<RetrievalDebugEntry> snapshot() {
        DebugSession session = currentSession.get();
        if (session == null) {
            return List.of();
        }
        return session.entries.values().stream()
            .filter(entry -> entry.selected || (entry.finalScore != null && entry.finalScore > 0.0))
            .sorted(
                Comparator.comparing(MutableEntry::selected).reversed()
                    .thenComparing(entry -> entry.finalScore == null ? 0.0 : entry.finalScore, Comparator.reverseOrder())
                    .thenComparing(entry -> entry.chunk.getChunkIndex() == null ? Integer.MAX_VALUE : entry.chunk.getChunkIndex())
            )
            .limit(MAX_DEBUG_ENTRIES)
            .map(MutableEntry::toResponse)
            .toList();
    }

    private Double maxNullable(Double current, Double next) {
        if (next == null || Double.isNaN(next)) {
            return current;
        }
        if (current == null || next > current) {
            return roundScore(next);
        }
        return current;
    }

    private Double roundScore(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(Math.max(0.0, value) * 1000.0) / 1000.0;
    }

    private String mergeReason(String current, String next) {
        if (current == null || current.isBlank()) {
            return next;
        }
        if (current.contains(next)) {
            return current;
        }
        return current + "；" + next;
    }

    /** 单次问答的调试会话数据。 */
    final class RetrievalDebugSession implements AutoCloseable {
        private final DebugSession debugSession;
        private boolean closed;

        private RetrievalDebugSession(DebugSession debugSession) {
            this.debugSession = debugSession;
        }

        List<RetrievalDebugEntry> snapshot() {
            if (closed || currentSession.get() != debugSession) {
                return List.of();
            }
            return RagRetrievalDebugService.this.snapshot();
        }

        @Override
        public void close() {
            if (!closed && currentSession.get() == debugSession) {
                clear();
            }
            closed = true;
        }
    }
    private static final class DebugSession {
        @SuppressWarnings("unused")
        private final String question;
        @SuppressWarnings("unused")
        private final Long materialId;
        @SuppressWarnings("unused")
        private final boolean generalChat;
        private final Map<Long, MutableEntry> entries = new LinkedHashMap<>();

        private DebugSession(String question, Long materialId, boolean generalChat) {
            this.question = question;
            this.materialId = materialId;
            this.generalChat = generalChat;
        }
    }

    /** 可变候选条目，最终会转换为不可变响应 DTO。 */
    private static final class MutableEntry {
        private final LearningMaterialEntity material;
        private final MaterialChunkEntity chunk;
        private final Set<String> routes = new LinkedHashSet<>();
        private Double rawScore;
        private Double rerankScore;
        private Double finalScore;
        private boolean selected;
        private String reason;
        private String selectedReason;
        private String penaltyReason;
        private String query;

        private MutableEntry(LearningMaterialEntity material, MaterialChunkEntity chunk) {
            this.material = material;
            this.chunk = chunk;
        }

        private boolean selected() {
            return selected;
        }

        private RetrievalDebugEntry toResponse() {
            return new RetrievalDebugEntry(
                material.getId(),
                chunk.getId(),
                material.getTitle(),
                chunk.getPageNo(),
                chunk.getChunkIndex(),
                excerptOf(chunk, query),
                new ArrayList<>(routes),
                rawScore,
                rerankScore,
                finalScore,
                selected,
                reason,
                selectedReason,
                penaltyReason
            );
        }

        private String excerptOf(MaterialChunkEntity chunk, String query) {
            String summary = chunk.getSummary();
            String text = chunk.getChunkText();
            String centered = centeredExcerpt(text, query);
            if (!centered.isBlank()) {
                return centered;
            }
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
            if (text == null || text.isBlank()) {
                return "";
            }
            return text.length() <= 180 ? text : text.substring(0, 180);
        }

        private String centeredExcerpt(String text, String query) {
            if (text == null || text.isBlank() || query == null || query.isBlank()) {
                return "";
            }
            String normalizedText = normalize(text);
            List<String> terms = significantTerms(query);
            for (String term : terms) {
                int normalizedIndex = normalizedText.indexOf(normalize(term));
                if (normalizedIndex < 0) {
                    continue;
                }
                int rawIndex = rawIndexNearNormalizedIndex(text, normalizedIndex);
                int start = Math.max(0, rawIndex - 70);
                int end = Math.min(text.length(), start + 220);
                if (end - start < 220) {
                    start = Math.max(0, end - 220);
                }
                return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
            }
            return "";
        }

        private List<String> significantTerms(String query) {
            List<String> terms = new ArrayList<>();
            Matcher matcher = Pattern.compile("[\\p{IsHan}a-zA-Z0-9_+#./-]{2,}").matcher(query);
            while (matcher.find()) {
                String term = matcher.group()
                    .replaceAll("^(什么是|何为|解释|定义)", "")
                    .replaceAll("(是什么|是啥|的定义|的含义|的概念)$", "")
                    .trim();
                if (term.length() >= 2) {
                    terms.add(term);
                }
            }
            return terms.stream().distinct().limit(6).toList();
        }

        private int rawIndexNearNormalizedIndex(String text, int normalizedIndex) {
            int normalizedCount = 0;
            for (int i = 0; i < text.length(); i++) {
                String ch = String.valueOf(text.charAt(i));
                if (normalize(ch).isBlank()) {
                    continue;
                }
                if (normalizedCount >= normalizedIndex) {
                    return i;
                }
                normalizedCount++;
            }
            return Math.min(text.length(), normalizedIndex);
        }

        private String normalize(String value) {
            if (value == null) {
                return "";
            }
            return value.toLowerCase().replaceAll("[\\s\\p{Punct}，。！？；：“”‘’（）《》、]+", "");
        }
    }
}
