package com.mytext.learningassistant.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.material.TemporaryMaterialContextEntity;
import com.mytext.learningassistant.material.TemporaryMaterialContextRepository;

import org.springframework.stereotype.Service;

/**
 * 智能问答临时资料上下文服务。
 *
 * <p>负责临时资料跨轮恢复、历史轻量化保存、单轮内切片检索和上下文合并。临时资料不进入资料管理，
 * 但在同一会话后续问题中仍应作为上下文可用。</p>
 */
@Service
public class TemporaryMaterialContextService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int MAX_TEMPORARY_MATERIAL_CONTEXT_CHARS = 16_000;
    private static final int TEMPORARY_MATERIAL_CHUNK_CHARS = 1_200;
    private static final int TEMPORARY_MATERIAL_CHUNK_OVERLAP = 160;
    private static final int MAX_TEMPORARY_MATERIAL_EXCERPTS = 8;
    private static final int MAX_TEMPORARY_MATERIAL_HISTORY_TEXT_CHARS = 20_000;

    private static final Pattern TEMPORARY_MATERIAL_TAIL_QUESTION_PATTERN = Pattern.compile(
        "(?i)(最后|末尾|结尾|后面|后半|后半部分|尾部|最后一页|last|ending|end of|tail|final)"
    );

    private final TemporaryMaterialContextRepository temporaryMaterialContextRepository;
    private final RagQuestionRepository ragQuestionRepository;

    public TemporaryMaterialContextService(
        TemporaryMaterialContextRepository temporaryMaterialContextRepository,
        RagQuestionRepository ragQuestionRepository
    ) {
        this.temporaryMaterialContextRepository = temporaryMaterialContextRepository;
        this.ragQuestionRepository = ragQuestionRepository;
    }

    public ChatRequest withStoredTemporaryMaterial(long userId, ChatRequest request) {
        if (request == null || request.temporaryMaterial() == null) {
            return request;
        }
        ChatTemporaryMaterial resolved = resolveStoredTemporaryMaterial(userId, request.temporaryMaterial());
        if (!hasUsableTemporaryMaterial(resolved)) {
            return request;
        }
        return withTemporaryMaterial(request, resolved);
    }

    public ChatRequest withConversationTemporaryMaterial(long userId, Long conversationId, ChatRequest request) {
        if (request == null || hasUsableTemporaryMaterial(request.temporaryMaterial()) || conversationId == null) {
            return request;
        }
        ChatTemporaryMaterial restored = latestTemporaryMaterialInConversation(userId, conversationId).orElse(null);
        if (!hasUsableTemporaryMaterial(restored)) {
            return request;
        }
        return withTemporaryMaterial(request, restored);
    }

    public String questionTemporaryMaterialJson(ChatRequest request) {
        ChatTemporaryMaterial material = compactTemporaryMaterialForHistory(request.temporaryMaterial());
        if (!hasUsableTemporaryMaterial(material)) {
            return null;
        }
        return writeJson(material);
    }

    public List<String> withTemporaryMaterialExcerpts(ChatRequest request, List<String> baseExcerpts) {
        List<String> temporaryExcerpts = temporaryMaterialExcerpts(request);
        if (temporaryExcerpts.isEmpty()) {
            return baseExcerpts;
        }
        List<String> merged = new ArrayList<>(temporaryExcerpts.size() + baseExcerpts.size());
        merged.addAll(temporaryExcerpts);
        merged.addAll(baseExcerpts);
        return limitTemporaryMergedExcerpts(merged);
    }

    public boolean hasUsableTemporaryMaterial(ChatTemporaryMaterial material) {
        if (material == null) {
            return false;
        }
        if (material.text() != null && !material.text().isBlank()) {
            return true;
        }
        return material.parts() != null && material.parts().stream().anyMatch(this::hasUsableTemporaryMaterial);
    }

    private ChatTemporaryMaterial compactTemporaryMaterialForHistory(ChatTemporaryMaterial material) {
        if (material == null) {
            return null;
        }
        List<ChatTemporaryMaterial> parts = material.parts() == null
            ? List.of()
            : material.parts().stream()
                .map(this::compactTemporaryMaterialForHistory)
                .filter(this::hasUsableTemporaryMaterial)
                .toList();
        String text = material.text() == null ? "" : material.text().trim();
        if (text.isBlank() && parts.isEmpty()) {
            return null;
        }
        String historyText = truncate(text, MAX_TEMPORARY_MATERIAL_HISTORY_TEXT_CHARS);
        String historyExcerpt = material.excerpt() == null || material.excerpt().isBlank()
            ? excerpt(historyText)
            : material.excerpt();
        return new ChatTemporaryMaterial(
            material.id(),
            material.title(),
            material.originalName(),
            material.sourceType(),
            historyText,
            historyExcerpt,
            material.fileSize(),
            parts.isEmpty() ? null : parts
        );
    }

    private Optional<ChatTemporaryMaterial> latestTemporaryMaterialInConversation(long userId, Long conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        List<RagQuestionEntity> questions = ragQuestionRepository.findByUserIdAndConversationIdOrderByCreatedAtAsc(userId, conversationId);
        for (int index = questions.size() - 1; index >= 0; index--) {
            ChatTemporaryMaterial material = readQuestionTemporaryMaterial(questions.get(index));
            if (hasUsableTemporaryMaterial(material)) {
                return Optional.of(resolveStoredTemporaryMaterial(userId, material));
            }
        }
        return Optional.empty();
    }

    private ChatTemporaryMaterial readQuestionTemporaryMaterial(RagQuestionEntity question) {
        String json = question.getQuestionTemporaryMaterialJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, ChatTemporaryMaterial.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private ChatTemporaryMaterial resolveStoredTemporaryMaterial(long userId, ChatTemporaryMaterial material) {
        return resolveStoredTemporaryMaterial(userId, material, 0);
    }

    private ChatTemporaryMaterial resolveStoredTemporaryMaterial(long userId, ChatTemporaryMaterial material, int depth) {
        if (material == null || depth > 4) {
            return material;
        }
        List<ChatTemporaryMaterial> resolvedParts = material.parts() == null
            ? List.of()
            : material.parts().stream()
                .map(part -> resolveStoredTemporaryMaterial(userId, part, depth + 1))
                .filter(this::hasUsableTemporaryMaterial)
                .toList();
        if (!resolvedParts.isEmpty()) {
            String mergedText = mergeTemporaryMaterialParts(resolvedParts);
            return new ChatTemporaryMaterial(
                material.id(),
                material.title(),
                material.originalName(),
                firstNonBlank(material.sourceType(), "MULTI"),
                mergedText,
                firstNonBlank(material.excerpt(), excerpt(mergedText)),
                material.fileSize(),
                resolvedParts
            );
        }
        String materialId = normalizeOptionalText(material.id());
        if (materialId == null) {
            return material;
        }
        Optional<TemporaryMaterialContextEntity> context = temporaryMaterialContextRepository.findByIdAndOwnerId(materialId, userId);
        if (context.isEmpty()) {
            return material;
        }
        TemporaryMaterialContextEntity stored = context.get();
        String text = stored.getText() == null ? "" : stored.getText();
        return new ChatTemporaryMaterial(
            stored.getId(),
            firstNonBlank(stored.getTitle(), material.title(), stored.getOriginalName(), material.originalName()),
            firstNonBlank(stored.getOriginalName(), material.originalName(), stored.getTitle(), material.title()),
            firstNonBlank(stored.getSourceType(), material.sourceType()),
            text,
            firstNonBlank(stored.getExcerpt(), material.excerpt(), excerpt(text)),
            stored.getFileSize() == null ? material.fileSize() : stored.getFileSize(),
            null
        );
    }

    private String mergeTemporaryMaterialParts(List<ChatTemporaryMaterial> parts) {
        List<String> texts = new ArrayList<>();
        for (int index = 0; index < parts.size(); index++) {
            ChatTemporaryMaterial part = parts.get(index);
            if (part == null || part.text() == null || part.text().isBlank()) {
                continue;
            }
            String title = firstNonBlank(part.title(), part.originalName(), "临时资料 " + (index + 1));
            String type = firstNonBlank(part.sourceType(), "UNKNOWN");
            texts.add("[临时资料 " + (index + 1) + "] " + title
                + "\n类型：" + type
                + "\n\n" + part.text());
        }
        return String.join("\n\n---\n\n", texts);
    }

    private ChatRequest withTemporaryMaterial(ChatRequest request, ChatTemporaryMaterial material) {
        return new ChatRequest(
            request.question(),
            request.materialId(),
            request.mode(),
            request.chunkId(),
            request.currentPageNo(),
            request.currentPageChunkIds(),
            request.selectedText(),
            request.images(),
            material,
            request.answerStyle(),
            request.history(),
            request.conversationId()
        );
    }

    private List<String> temporaryMaterialExcerpts(ChatRequest request) {
        if (request == null || request.temporaryMaterial() == null) {
            return List.of();
        }
        ChatTemporaryMaterial material = request.temporaryMaterial();
        if (material.text() == null || material.text().isBlank()) {
            return List.of();
        }
        List<TemporaryContextChunk> chunks = splitTemporaryMaterial(material.text());
        if (chunks.isEmpty()) {
            return List.of();
        }
        Bm25Scorer scorer = new Bm25Scorer(chunks.stream()
            .map(chunk -> new Bm25Scorer.ChunkData(chunk.id(), chunk.text()))
            .toList());
        Set<String> queryTokens = scorer.tokenize(rewriteQuestionForRetrieval(request.question(), request.history()));
        List<TemporaryContextChunk> ranked = chunks.stream()
            .map(chunk -> chunk.withScore(scorer.score(queryTokens, chunk.id())))
            .filter(chunk -> chunk.score() > 0.0)
            .sorted(Comparator.comparingDouble(TemporaryContextChunk::score).reversed())
            .limit(MAX_TEMPORARY_MATERIAL_EXCERPTS)
            .collect(Collectors.toCollection(ArrayList::new));
        if (ranked.isEmpty()) {
            ranked.add(chunks.get(0).withScore(0.2));
            if (chunks.size() > 1) {
                ranked.add(chunks.get(chunks.size() - 1).withScore(0.15));
            }
        }
        if (asksTemporaryMaterialTail(request.question())) {
            TemporaryContextChunk tail = chunks.get(chunks.size() - 1).withScore(1.0);
            boolean alreadySelected = ranked.stream().anyMatch(chunk -> chunk.id() == tail.id());
            if (!alreadySelected) {
                ranked.add(0, tail);
            }
        }
        return ranked.stream()
            .limit(MAX_TEMPORARY_MATERIAL_EXCERPTS)
            .map(chunk -> temporarySourceContext(material, chunk))
            .toList();
    }

    private List<TemporaryContextChunk> splitTemporaryMaterial(String text) {
        String normalized = cleanExcerptText(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<TemporaryContextChunk> chunks = new ArrayList<>();
        int index = 0;
        int cursor = 0;
        while (cursor < normalized.length()) {
            int end = Math.min(normalized.length(), cursor + TEMPORARY_MATERIAL_CHUNK_CHARS);
            if (end < normalized.length()) {
                int sentenceEnd = findTemporaryChunkBoundary(normalized, cursor, end);
                if (sentenceEnd > cursor + TEMPORARY_MATERIAL_CHUNK_CHARS / 2) {
                    end = sentenceEnd;
                }
            }
            String chunkText = normalized.substring(cursor, end).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new TemporaryContextChunk(index + 1L, index, chunkText, 0.0));
                index++;
            }
            if (end >= normalized.length()) {
                break;
            }
            cursor = Math.max(end - TEMPORARY_MATERIAL_CHUNK_OVERLAP, cursor + 1);
        }
        return chunks;
    }

    private int findTemporaryChunkBoundary(String text, int start, int preferredEnd) {
        int lowerBound = start + TEMPORARY_MATERIAL_CHUNK_CHARS / 2;
        for (int i = preferredEnd; i > lowerBound; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？' || c == ';' || c == '；' || c == '.') {
                return i;
            }
        }
        return preferredEnd;
    }

    private String temporarySourceContext(ChatTemporaryMaterial material, TemporaryContextChunk chunk) {
        String title = firstNonBlank(material.title(), material.originalName(), "临时资料");
        String type = firstNonBlank(material.sourceType(), "UNKNOWN");
        return "[临时资料片段]《" + title + "》"
            + type + "/片段 " + (chunk.index() + 1)
            + "\n[片段内容]\n原文：" + truncate(chunk.text(), 2_400);
    }

    private List<String> limitTemporaryMergedExcerpts(List<String> excerpts) {
        List<String> limited = new ArrayList<>();
        int totalChars = 0;
        for (String excerpt : excerpts) {
            if (excerpt == null || excerpt.isBlank()) {
                continue;
            }
            if (!limited.isEmpty() && totalChars + excerpt.length() > MAX_TEMPORARY_MATERIAL_CONTEXT_CHARS) {
                break;
            }
            limited.add(excerpt);
            totalChars += excerpt.length();
        }
        return limited;
    }

    private boolean asksTemporaryMaterialTail(String question) {
        return question != null && TEMPORARY_MATERIAL_TAIL_QUESTION_PATTERN.matcher(question).find();
    }

    private String rewriteQuestionForRetrieval(String question, List<ChatMessage> history) {
        String current = question == null ? "" : question.trim();
        if (current.isBlank() || history == null || history.isEmpty()) {
            return current;
        }
        String topic = recentConversationTopic(history);
        if (topic == null || topic.isBlank()) {
            return current;
        }
        String normalizedQuestion = normalizeForTermMatch(current);
        String normalizedTopic = normalizeForTermMatch(topic);
        if (normalizedTopic.isBlank() || normalizedQuestion.contains(normalizedTopic)) {
            return current;
        }
        if (isFollowUpQuestion(current)) {
            return topic + " " + current;
        }
        return current;
    }

    private String recentConversationTopic(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            String topic = extractConversationTopic(message.content());
            if (topic != null && !topic.isBlank()) {
                return topic;
            }
        }
        return null;
    }

    private String extractConversationTopic(String text) {
        String normalized = cleanExcerptText(text);
        if (normalized.length() < 2) {
            return null;
        }
        String[] candidates = normalized.split("[，。！？,.;；：:\\s]+");
        for (String candidate : candidates) {
            String value = normalizeOptionalText(candidate);
            if (value != null && value.length() >= 2 && value.length() <= 32) {
                return value;
            }
        }
        return normalized.length() <= 32 ? normalized : normalized.substring(0, 32);
    }

    private boolean isFollowUpQuestion(String question) {
        String normalized = normalizeForTermMatch(question);
        return normalized.length() <= 18
            && (normalized.contains("它")
                || normalized.contains("这个")
                || normalized.contains("该")
                || normalized.contains("上述")
                || normalized.contains("前面")
                || normalized.contains("刚才"));
    }

    private String normalizeForTermMatch(String text) {
        return text == null ? "" : text.toLowerCase().replaceAll("\\s+", "");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalizeOptionalText(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxChars) {
            return normalized;
        }
        return normalized.substring(0, maxChars) + "\n[内容过长，已截断]";
    }

    private String excerpt(String text) {
        String normalized = cleanExcerptText(text);
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160) + "...";
    }

    private String cleanExcerptText(String text) {
        return (text == null ? "" : text).trim().replaceAll("\\s+", " ");
    }

    private String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private record TemporaryContextChunk(long id, int index, String text, double score) {
        private TemporaryContextChunk withScore(double nextScore) {
            return new TemporaryContextChunk(id, index, text, nextScore);
        }
    }
}
