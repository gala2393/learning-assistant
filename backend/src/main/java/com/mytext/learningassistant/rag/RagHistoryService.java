package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.LearningMaterialRepository;
import com.mytext.learningassistant.material.MaterialParseStatus;
import com.mytext.learningassistant.material.MaterialTextStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG 历史、收藏和反馈服务。
 *
 * <p>这一层只处理问答历史的读取、重命名、置顶、删除、收藏和反馈，不参与检索、prompt 构造和模型调用。
 * {@link RagService} 暂时保留同名 public 方法作为门面，避免 Controller 和前端 API 行为变化。</p>
 */
@Service
public class RagHistoryService {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LearningMaterialRepository learningMaterialRepository;
    private final RagQuestionRepository ragQuestionRepository;
    private final RagQuestionSourceRepository ragQuestionSourceRepository;
    private final RagConversationMemoryRepository ragConversationMemoryRepository;
    private final RagFeedbackRepository ragFeedbackRepository;
    private final RagEvaluationRepository ragEvaluationRepository;
    private final UserFavoriteRepository userFavoriteRepository;
    private final RagSourceService ragSourceService;

    public RagHistoryService(
        LearningMaterialRepository learningMaterialRepository,
        RagQuestionRepository ragQuestionRepository,
        RagQuestionSourceRepository ragQuestionSourceRepository,
        RagConversationMemoryRepository ragConversationMemoryRepository,
        RagFeedbackRepository ragFeedbackRepository,
        RagEvaluationRepository ragEvaluationRepository,
        UserFavoriteRepository userFavoriteRepository,
        RagSourceService ragSourceService
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.ragQuestionRepository = ragQuestionRepository;
        this.ragQuestionSourceRepository = ragQuestionSourceRepository;
        this.ragConversationMemoryRepository = ragConversationMemoryRepository;
        this.ragFeedbackRepository = ragFeedbackRepository;
        this.ragEvaluationRepository = ragEvaluationRepository;
        this.userFavoriteRepository = userFavoriteRepository;
        this.ragSourceService = ragSourceService;
    }

    @Transactional(readOnly = true)
    public List<RagHistoryItemResponse> history(long userId) {
        return latestQuestionsByConversation(userId).stream()
            .map(question -> toHistoryItem(userId, question))
            .toList();
    }

    @Transactional(readOnly = true)
    public RagHistoryDetailResponse historyDetail(long userId, long questionId) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "Question not found"));
        List<RagQuestionEntity> conversationQuestions = questionsInConversation(userId, question);
        RagQuestionEntity latestQuestion = conversationQuestions.get(conversationQuestions.size() - 1);
        List<RagSourceResponse> sources = ragQuestionSourceRepository.findByQuestionIdOrderByRankScoreDesc(latestQuestion.getId()).stream()
            .map(ragSourceService::toSourceResponse)
            .toList();
        return toHistoryDetail(userId, latestQuestion, sources, conversationQuestions);
    }

    @Transactional(readOnly = true)
    public RagHistoryDetailResponse latestMaterialHistory(long userId, long materialId) {
        validateCurrentMaterialForChat(userId, materialId);
        return latestMaterialHistoryQuestion(userId, materialId)
            .map(question -> historyDetail(userId, question.getId()))
            .orElse(null);
    }

    @Transactional(readOnly = true)
    public List<RagHistoryItemResponse> materialHistory(long userId, long materialId) {
        validateCurrentMaterialForChat(userId, materialId);
        Map<Long, RagQuestionEntity> latestByConversation = new LinkedHashMap<>();
        for (RagQuestionEntity question : materialHistoryQuestions(userId, materialId)) {
            latestByConversation.putIfAbsent(effectiveConversationId(question), question);
        }
        return latestByConversation.values().stream()
            .map(question -> toHistoryItem(userId, question))
            .toList();
    }

    @Transactional
    public RagHistoryItemResponse renameHistory(long userId, long questionId, String title) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "History not found"));
        question.setTitle(normalizeHistoryTitle(title, question.getQuestionText()));
        return toHistoryItem(userId, ragQuestionRepository.save(question));
    }

    @Transactional
    public RagHistoryItemResponse togglePinHistory(long userId, long questionId) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "History not found"));
        question.setPinned(!question.isPinned());
        return toHistoryItem(userId, ragQuestionRepository.save(question));
    }

    @Transactional
    public RagFeedbackResponse submitFeedback(long userId, long questionId, RagFeedbackRequest request) {
        ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "Question not found"));
        int rating = normalizeFeedbackRating(request == null ? null : request.rating());
        String comment = normalizeFeedbackComment(request == null ? null : request.comment());

        RagFeedbackEntity feedback = ragFeedbackRepository.findByQuestionIdAndUserId(questionId, userId)
            .orElseGet(RagFeedbackEntity::new);
        feedback.setUserId(userId);
        feedback.setQuestionId(questionId);
        feedback.setRating(rating);
        feedback.setComment(comment);
        return toFeedbackResponse(ragFeedbackRepository.save(feedback));
    }

    @Transactional
    public void deleteHistory(long userId, long questionId) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "Question not found"));
        Long conversationId = effectiveConversationId(question);
        for (RagQuestionEntity conversationQuestion : questionsInConversation(userId, question)) {
            Long id = conversationQuestion.getId();
            userFavoriteRepository.deleteByUserIdAndQuestionId(userId, id);
            ragQuestionSourceRepository.deleteByQuestionId(id);
            ragFeedbackRepository.deleteByQuestionId(id);
            ragEvaluationRepository.deleteByQuestionId(id);
            ragQuestionRepository.delete(conversationQuestion);
        }
        ragConversationMemoryRepository.findByUserIdAndConversationId(userId, conversationId)
            .ifPresent(ragConversationMemoryRepository::delete);
    }

    @Transactional
    public void clearHistory(long userId) {
        List<Long> questionIds = ragQuestionRepository.findByUserIdOrderByPinnedDescCreatedAtDesc(userId).stream()
            .map(RagQuestionEntity::getId)
            .toList();
        if (questionIds.isEmpty()) {
            return;
        }
        userFavoriteRepository.deleteByUserIdAndQuestionIdIn(userId, questionIds);
        ragQuestionSourceRepository.deleteByQuestionIdIn(questionIds);
        ragFeedbackRepository.deleteByQuestionIdIn(questionIds);
        ragEvaluationRepository.deleteByQuestionIdIn(questionIds);
        ragQuestionRepository.deleteByUserId(userId);
        ragConversationMemoryRepository.deleteByUserId(userId);
    }

    @Transactional
    public FavoriteItemResponse addFavorite(long userId, FavoriteRequest request) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(request.questionId(), userId)
            .orElseThrow(() -> new BusinessException(404, "问答不存在"));
        UserFavoriteEntity favorite = userFavoriteRepository.findByUserIdAndQuestionId(userId, request.questionId())
            .orElseGet(UserFavoriteEntity::new);
        favorite.setUserId(userId);
        favorite.setQuestionId(question.getId());
        if (favorite.getCreatedAt() == null) {
            favorite.setCreatedAt(LocalDateTime.now());
        }
        UserFavoriteEntity saved = userFavoriteRepository.save(favorite);
        return toFavoriteItem(saved, question, userId);
    }

    @Transactional
    public void deleteFavorite(long userId, long favoriteId) {
        UserFavoriteEntity favorite = userFavoriteRepository.findByIdAndUserId(favoriteId, userId)
            .orElseThrow(() -> new BusinessException(404, "收藏不存在"));
        userFavoriteRepository.delete(favorite);
    }

    @Transactional(readOnly = true)
    public List<FavoriteItemResponse> favorites(long userId) {
        return userFavoriteRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(favorite -> ragQuestionRepository.findByIdAndUserId(favorite.getQuestionId(), userId)
                .map(question -> toFavoriteItem(favorite, question, userId))
                .orElse(null))
            .filter(item -> item != null)
            .toList();
    }

    private FavoriteItemResponse toFavoriteItem(UserFavoriteEntity favorite, RagQuestionEntity question, long userId) {
        return new FavoriteItemResponse(
            favorite.getId(),
            question.getId(),
            effectiveConversationId(question),
            question.getQuestionText(),
            question.getAnswerText(),
            formatDateTime(favorite.getCreatedAt()),
            toConversationMessages(questionsInConversation(userId, question))
        );
    }

    private RagHistoryItemResponse toHistoryItem(long userId, RagQuestionEntity question) {
        UserFavoriteEntity favorite = userFavoriteRepository.findByUserIdAndQuestionId(userId, question.getId())
            .orElse(null);
        return new RagHistoryItemResponse(
            question.getId(),
            effectiveConversationId(question),
            question.getTitle(),
            question.getQuestionText(),
            question.getAnswerText(),
            formatDateTime(question.getCreatedAt()),
            favorite == null ? null : favorite.getId(),
            favorite != null,
            question.isPinned()
        );
    }

    private RagHistoryDetailResponse toHistoryDetail(
        long userId,
        RagQuestionEntity question,
        List<RagSourceResponse> sources,
        List<RagQuestionEntity> conversationQuestions
    ) {
        UserFavoriteEntity favorite = userFavoriteRepository.findByUserIdAndQuestionId(userId, question.getId())
            .orElse(null);
        return new RagHistoryDetailResponse(
            question.getId(),
            effectiveConversationId(question),
            question.getTitle(),
            question.getQuestionText(),
            question.getAnswerText(),
            formatDateTime(question.getCreatedAt()),
            toConversationMessages(conversationQuestions),
            sources,
            readRetrievalDebug(question),
            favorite == null ? null : favorite.getId(),
            favorite != null,
            question.isPinned()
        );
    }

    private RagFeedbackResponse toFeedbackResponse(RagFeedbackEntity feedback) {
        return new RagFeedbackResponse(
            feedback.getId(),
            feedback.getQuestionId(),
            feedback.getRating(),
            feedback.getComment(),
            formatDateTime(feedback.getUpdatedAt())
        );
    }

    private List<RagQuestionEntity> latestQuestionsByConversation(long userId) {
        Map<Long, RagQuestionEntity> latestByConversation = new LinkedHashMap<>();
        for (RagQuestionEntity question : ragQuestionRepository.findByUserIdOrderByPinnedDescCreatedAtDesc(userId)) {
            latestByConversation.putIfAbsent(effectiveConversationId(question), question);
        }
        return latestByConversation.values().stream().toList();
    }

    private List<RagQuestionEntity> materialHistoryQuestions(long userId, long materialId) {
        Map<Long, RagQuestionEntity> questions = new LinkedHashMap<>();
        for (RagQuestionEntity question : ragQuestionRepository.findByUserIdAndMaterialIdOrderByCreatedAtDesc(userId, materialId)) {
            questions.put(question.getId(), question);
        }
        for (Long questionId : ragQuestionSourceRepository.findQuestionIdsByMaterialIdOrderByCreatedAtDesc(materialId)) {
            if (questionId == null || questions.containsKey(questionId)) {
                continue;
            }
            ragQuestionRepository.findByIdAndUserId(questionId, userId)
                .ifPresent(question -> questions.put(question.getId(), question));
        }
        return questions.values().stream()
            .sorted(this::compareCreatedAtDesc)
            .toList();
    }

    private Optional<RagQuestionEntity> latestMaterialHistoryQuestion(long userId, long materialId) {
        return materialHistoryQuestions(userId, materialId).stream().findFirst();
    }

    private int compareCreatedAtDesc(RagQuestionEntity left, RagQuestionEntity right) {
        return Comparator
            .comparing(RagQuestionEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed()
            .compare(left, right);
    }

    private List<RagQuestionEntity> questionsInConversation(long userId, RagQuestionEntity question) {
        Long conversationId = effectiveConversationId(question);
        List<RagQuestionEntity> conversationQuestions = ragQuestionRepository
            .findByUserIdAndConversationIdOrderByCreatedAtAsc(userId, conversationId);
        if (conversationQuestions.isEmpty()) {
            return List.of(question);
        }
        return conversationQuestions;
    }

    private List<RagHistoryMessageResponse> toConversationMessages(List<RagQuestionEntity> questions) {
        List<RagHistoryMessageResponse> messages = new ArrayList<>();
        for (RagQuestionEntity question : questions) {
            messages.add(new RagHistoryMessageResponse(
                question.getId(),
                "user",
                question.getQuestionText(),
                readQuestionImages(question),
                readQuestionTemporaryMaterial(question)
            ));
            messages.add(new RagHistoryMessageResponse(
                question.getId(),
                "assistant",
                question.getAnswerText(),
                List.of(),
                null
            ));
        }
        return messages;
    }

    private List<ChatImage> readQuestionImages(RagQuestionEntity question) {
        String json = question.getQuestionImagesJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<ChatImage> images = new ArrayList<>();
            for (JsonNode node : root) {
                images.add(new ChatImage(
                    textValue(node, "dataUrl"),
                    textValue(node, "base64Data"),
                    textValue(node, "mediaType")
                ));
            }
            return images;
        } catch (Exception exception) {
            return List.of();
        }
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

    private List<RetrievalDebugEntry> readRetrievalDebug(RagQuestionEntity question) {
        String json = question.getRetrievalDebugJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readerForListOf(RetrievalDebugEntry.class).readValue(json);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String textValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void validateCurrentMaterialForChat(long userId, Long materialId) {
        if (materialId == null) {
            throw new BusinessException(400, "materialId is required for current material chat");
        }
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElseThrow(() -> new BusinessException(404, "material not found"));
        if (!isMaterialReadyForRetrieval(material)) {
            throw new BusinessException(400, "material has not been parsed successfully");
        }
    }

    private boolean isMaterialReadyForRetrieval(LearningMaterialEntity material) {
        if (material == null) {
            return false;
        }
        if (material.getTextStatus() == MaterialTextStatus.PARTIAL) {
            return true;
        }
        return material.getParseStatus() == MaterialParseStatus.SUCCESS
            || material.getTextStatus() == MaterialTextStatus.READY
            || (material.getChunkCount() != null && material.getChunkCount() > 0);
    }

    private Long effectiveConversationId(RagQuestionEntity question) {
        return question.getConversationId() == null ? question.getId() : question.getConversationId();
    }

    private String normalizeHistoryTitle(String title, String fallback) {
        String value = title == null ? "" : title.trim();
        if (value.isBlank()) {
            value = fallback == null ? "" : fallback.trim();
        }
        value = value.replaceAll("\\s+", " ");
        if (value.isBlank()) {
            value = "未命名会话";
        }
        return value;
    }

    private int normalizeFeedbackRating(Integer rating) {
        if (rating == null || (rating != 1 && rating != -1)) {
            throw new BusinessException(400, "rating must be 1 or -1");
        }
        return rating;
    }

    private String normalizeFeedbackComment(String comment) {
        String normalized = comment == null ? "" : comment.trim();
        if (normalized.length() > 1000) {
            throw new BusinessException(400, "comment is too long");
        }
        return normalized.isBlank() ? null : normalized;
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.format(DATETIME_FORMATTER);
    }
}
