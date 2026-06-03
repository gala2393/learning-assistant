package com.mytext.learningassistant.rag;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;

import javax.imageio.ImageIO;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.admin.UsageRecordEntity;
import com.mytext.learningassistant.admin.UsageRecordRepository;
import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.embedding.EmbeddingClient;
import com.mytext.learningassistant.embedding.EmbeddingProperties;
import com.mytext.learningassistant.llm.LlmCompletion;
import com.mytext.learningassistant.llm.LlmImage;
import com.mytext.learningassistant.llm.ThirdPartyLlmClient;
import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.LearningMaterialRepository;
import com.mytext.learningassistant.material.MaterialChunkEntity;
import com.mytext.learningassistant.material.MaterialChunkRepository;
import com.mytext.learningassistant.material.MaterialParseStatus;
import com.mytext.learningassistant.material.MaterialSummaryStatus;
import com.mytext.learningassistant.material.MaterialSourceType;
import com.mytext.learningassistant.rerank.RerankCandidate;
import com.mytext.learningassistant.rerank.RerankedCandidate;
import com.mytext.learningassistant.rerank.RerankerClient;
import com.mytext.learningassistant.user.UserRepository;
import com.mytext.learningassistant.user.UserRole;
import com.mytext.learningassistant.vector.VectorSearchResult;
import com.mytext.learningassistant.vector.VectorStoreClient;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagService {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_CONTEXT_CHARS = 10_000;
    private static final int BM25_CACHE_MAX_ENTRIES = 64;
    private static final int RETRIEVAL_CACHE_MAX_ENTRIES = 128;
    private static final int USER_DAILY_CHAT_LIMIT = 100;
    private static final int MAX_USER_IMAGES_PER_REQUEST = 4;
    private static final int MAX_USER_IMAGE_BASE64_CHARS = 2_800_000;
    private static final Pattern LOCAL_CONTEXT_QUESTION_PATTERN = Pattern.compile(
        "(?i)(this|current|page|chapter|section|paragraph|chunk|slide|这里|这页|这一页|本页|当前页|这个页面|这章|这一章|本章|当前章节|这一节|本节|当前内容|这段|这一段|这部分|这里面|讲什么|说什么|主要内容|总结一下|概括)"
    );
    private static final Pattern MATERIAL_OVERVIEW_QUESTION_PATTERN = Pattern.compile(
        "(?i)(what\\s+(?:is|does).*?(?:book|document|material)|summari[sz]e|overview|introduce|about|"
            + "\\u8fd9(?:\\u662f)?\\u4ec0\\u4e48(?:\\u4e66|\\u8d44\\u6599|\\u6587\\u6863|\\u6587\\u4ef6)|"
            + "\\u8fd9\\u672c\\u4e66|\\u8fd9\\u4efd(?:\\u8d44\\u6599|\\u6587\\u6863|\\u6587\\u4ef6)|"
            + "\\u8bb2\\u4ec0\\u4e48|\\u4ecb\\u7ecd\\u4e00\\u4e0b|\\u7b80\\u4ecb|\\u6982\\u62ec|\\u4e3b\\u8981\\u5185\\u5bb9)"
    );
    private static final Pattern TERM_DEFINITION_PATTERN = Pattern.compile(
        "(?i)(?:什么是|何为|解释一下|解释|定义|含义|概念|define|definition of|what is|what are)\\s*[\"“'‘]?([^\"”'’？?，,。；;：:\\n]{2,80})[\"”'’]?"
    );

    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final RagQuestionRepository ragQuestionRepository;
    private final RagQuestionSourceRepository ragQuestionSourceRepository;
    private final RagFeedbackRepository ragFeedbackRepository;
    private final RagEvaluationRepository ragEvaluationRepository;
    private final RagEvaluationSuiteRepository ragEvaluationSuiteRepository;
    private final RagEvaluationSuiteCaseRepository ragEvaluationSuiteCaseRepository;
    private final RagEvaluationSuiteRunRepository ragEvaluationSuiteRunRepository;
    private final UserFavoriteRepository userFavoriteRepository;
    private final MaterialSummaryRepository materialSummaryRepository;
    private final UserRepository userRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final ThirdPartyLlmClient thirdPartyLlmClient;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties embeddingProperties;
    private final RerankerClient rerankerClient;
    private final VectorStoreClient vectorStoreClient;
    private final QueryExpansionProperties queryExpansionProperties;
    private final Path storageRoot;
    private final ConcurrentMap<String, Bm25IndexCacheEntry> bm25IndexCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RetrievalCacheEntry> retrievalResultCache = new ConcurrentHashMap<>();

    public RagService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        RagQuestionRepository ragQuestionRepository,
        RagQuestionSourceRepository ragQuestionSourceRepository,
        RagFeedbackRepository ragFeedbackRepository,
        RagEvaluationRepository ragEvaluationRepository,
        RagEvaluationSuiteRepository ragEvaluationSuiteRepository,
        RagEvaluationSuiteCaseRepository ragEvaluationSuiteCaseRepository,
        RagEvaluationSuiteRunRepository ragEvaluationSuiteRunRepository,
        UserFavoriteRepository userFavoriteRepository,
        MaterialSummaryRepository materialSummaryRepository,
        UserRepository userRepository,
        UsageRecordRepository usageRecordRepository,
        ThirdPartyLlmClient thirdPartyLlmClient,
        EmbeddingClient embeddingClient,
        EmbeddingProperties embeddingProperties,
        RerankerClient rerankerClient,
        VectorStoreClient vectorStoreClient,
        QueryExpansionProperties queryExpansionProperties,
        @Value("${app.storage-dir:${user.dir}/target/learning-assistant-files}") String storageDir
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.ragQuestionRepository = ragQuestionRepository;
        this.ragQuestionSourceRepository = ragQuestionSourceRepository;
        this.ragFeedbackRepository = ragFeedbackRepository;
        this.ragEvaluationRepository = ragEvaluationRepository;
        this.ragEvaluationSuiteRepository = ragEvaluationSuiteRepository;
        this.ragEvaluationSuiteCaseRepository = ragEvaluationSuiteCaseRepository;
        this.ragEvaluationSuiteRunRepository = ragEvaluationSuiteRunRepository;
        this.userFavoriteRepository = userFavoriteRepository;
        this.materialSummaryRepository = materialSummaryRepository;
        this.userRepository = userRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.thirdPartyLlmClient = thirdPartyLlmClient;
        this.embeddingClient = embeddingClient;
        this.embeddingProperties = embeddingProperties;
        this.rerankerClient = rerankerClient;
        this.vectorStoreClient = vectorStoreClient;
        this.queryExpansionProperties = queryExpansionProperties;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Transactional
    public RagChatResponse chat(long userId, ChatRequest request) {
        ensureChatUsageAvailable(userId);
        boolean generalChat = isGeneralChat(request);
        if (thirdPartyLlmClient.isModelIdentityQuestion(request.question())) {
            return chatModelIdentity(userId, request);
        }
        if (isMaterialChat(request) || (!generalChat && request.materialId() != null)) {
            validateCurrentMaterialForChat(userId, request.materialId());
        }
        List<ScoredChunk> selectedChunks = selectContextChunks(userId, request, generalChat);

        List<String> excerpts = buildExcerpts(request, selectedChunks);
        List<LlmImage> images = new ArrayList<>(userImages(request));
        images.addAll(selectedChunks.stream()
            .flatMap(chunk -> loadChunkImages(chunk.material(), chunk.chunk()).stream())
            .limit(Math.max(0, MAX_IMAGES_PER_REQUEST - images.size()))
            .toList());
        images = new ArrayList<>(images.stream()
            .limit(MAX_IMAGES_PER_REQUEST)
            .toList());

        if (request.chunkId() != null && images.size() < MAX_IMAGES_PER_REQUEST) {
            for (ScoredChunk currentChunk : findChunksById(userId, request)) {
                appendImages(images, loadChunkImages(currentChunk.material(), currentChunk.chunk()));
            }
        }

        if (images.size() < MAX_IMAGES_PER_REQUEST && !generalChat) {
            collectImagesFromMaterialChunks(userId, request.materialId(), selectedChunks, images);
        }

        String questionWithContext = buildQuestionWithHistory(request.question(), request.history(), generalChat);
        String llmQuestion = thirdPartyLlmClient.isModelIdentityQuestion(request.question())
            ? request.question()
            : questionWithContext;
        LlmCompletion rawCompletion = answerWithThirdParty(userId, llmQuestion, excerpts, images, generalChat, request.answerStyle())
            .orElseGet(() -> new LlmCompletion(
                generalChat
                    ? buildGeneralFallbackAnswer(request.question())
                    : buildCleanAnswer(request.question(), selectedChunks, request.answerStyle()),
                "local-rag-demo"
            ));
        TokenUsage usage = completionUsage(rawCompletion, llmQuestion, rawCompletion.content());
        LlmCompletion completion = new LlmCompletion(
            generalChat
                ? decorateGeneralAnswer(rawCompletion.content())
                : decorateAnswer(request, rawCompletion.content(), selectedChunks),
            rawCompletion.modelName(),
            usage.promptTokens(),
            usage.completionTokens(),
            usage.totalTokens(),
            rawCompletion.customModel()
        );

        RagQuestionEntity question = new RagQuestionEntity();
        question.setUserId(userId);
        question.setConversationId(resolveConversationId(userId, request.conversationId()));
        question.setQuestionText(request.question());
        question.setTitle(buildConversationTitle(request.question()));
        question.setAnswerText(completion.content());
        question.setModelName(completion.modelName());
        question.setPromptTokens(completion.promptTokens());
        question.setCompletionTokens(completion.completionTokens());
        question.setTotalTokens(completion.totalTokens());
        question.setCustomModel(completion.customModel());
        question.setQuestionStatus(QuestionStatus.SUCCESS);
        RagQuestionEntity savedQuestion = ragQuestionRepository.save(question);
        savedQuestion = ensureConversationId(savedQuestion);

        List<RagQuestionSourceEntity> sourceEntities = saveSources(savedQuestion.getId(), selectedChunks);
        recordUsageLog(userId, "RAG_CHAT", savedQuestion.getId(), request, completion);

        return new RagChatResponse(
            savedQuestion.getId(),
            savedQuestion.getConversationId(),
            savedQuestion.getQuestionText(),
            savedQuestion.getAnswerText(),
            sourceEntities.stream().map(this::toSourceResponse).toList(),
            savedQuestion.getCreatedAt() == null ? null : savedQuestion.getCreatedAt().format(DATETIME_FORMATTER)
        );
    }

    private RagChatResponse chatModelIdentity(long userId, ChatRequest request) {
        LlmCompletion completion = thirdPartyLlmClient.currentModelCompletion(userId);
        TokenUsage usage = estimateUsage(request.question(), completion.content());
        completion = new LlmCompletion(
            completion.content(),
            completion.modelName(),
            usage.promptTokens(),
            usage.completionTokens(),
            usage.totalTokens(),
            completion.customModel()
        );

        RagQuestionEntity question = new RagQuestionEntity();
        question.setUserId(userId);
        question.setConversationId(resolveConversationId(userId, request.conversationId()));
        question.setQuestionText(request.question());
        question.setTitle(buildConversationTitle(request.question()));
        question.setAnswerText(completion.content());
        question.setModelName(completion.modelName());
        question.setPromptTokens(completion.promptTokens());
        question.setCompletionTokens(completion.completionTokens());
        question.setTotalTokens(completion.totalTokens());
        question.setCustomModel(completion.customModel());
        question.setQuestionStatus(QuestionStatus.SUCCESS);
        RagQuestionEntity savedQuestion = ensureConversationId(ragQuestionRepository.save(question));
        recordUsageLog(userId, "RAG_CHAT", savedQuestion.getId(), request, completion);

        return new RagChatResponse(
            savedQuestion.getId(),
            savedQuestion.getConversationId(),
            savedQuestion.getQuestionText(),
            savedQuestion.getAnswerText(),
            List.of(),
            savedQuestion.getCreatedAt() == null ? null : savedQuestion.getCreatedAt().format(DATETIME_FORMATTER)
        );
    }

    private java.util.Optional<LlmCompletion> answerWithThirdParty(
        long userId,
        String question,
        List<String> excerpts,
        List<LlmImage> images,
        boolean generalChat,
        String answerStyle
    ) {
        boolean customModel = thirdPartyLlmClient.hasActiveUserConfig(userId);
        java.util.Optional<LlmCompletion> completion;
        if (customModel) {
            completion = thirdPartyLlmClient.answer(userId, question, excerpts, images, generalChat, answerStyle);
        } else {
            completion = isHomeworkStyle(answerStyle)
                ? thirdPartyLlmClient.answer(question, excerpts, images, generalChat, answerStyle)
                : thirdPartyLlmClient.answer(question, excerpts, images, generalChat);
        }
        if (completion != null && completion.isPresent()) {
            return completion;
        }
        return completion == null ? java.util.Optional.empty() : completion;
    }

    @Transactional
    public RagStreamResult chatStream(long userId, ChatRequest request, java.util.function.Consumer<String> onChunk) {
        ensureChatUsageAvailable(userId);
        boolean generalChat = isGeneralChat(request);
        if (thirdPartyLlmClient.isModelIdentityQuestion(request.question())) {
            return streamModelIdentity(userId, request, onChunk);
        }
        if (isMaterialChat(request) || (!generalChat && request.materialId() != null)) {
            validateCurrentMaterialForChat(userId, request.materialId());
        }

        List<ScoredChunk> selectedChunks = selectContextChunks(userId, request, generalChat);
        List<String> excerpts = buildExcerpts(request, selectedChunks);

        if (excerpts.isEmpty() && !generalChat && request.materialId() != null) {
            LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(request.materialId(), userId).orElse(null);
            if (material != null) {
                excerpts = List.of("[当前资料]《" + material.getTitle() + "》\n用户正在阅读这份资料，请基于该资料内容回答问题。");
            }
        }

        List<LlmImage> images = new ArrayList<>(userImages(request));
        images.addAll(selectedChunks.stream()
            .flatMap(chunk -> loadChunkImages(chunk.material(), chunk.chunk()).stream())
            .limit(Math.max(0, MAX_IMAGES_PER_REQUEST - images.size()))
            .toList());
        images = new ArrayList<>(images.stream()
            .limit(MAX_IMAGES_PER_REQUEST)
            .toList());

        if (images.size() < MAX_IMAGES_PER_REQUEST && !generalChat) {
            collectImagesFromMaterialChunks(userId, request.materialId(), selectedChunks, images);
        }

        String questionWithContext = buildQuestionWithHistory(request.question(), request.history(), generalChat);
        String llmQuestion = thirdPartyLlmClient.isModelIdentityQuestion(request.question())
            ? request.question()
            : questionWithContext;

        boolean customModel = thirdPartyLlmClient.hasActiveUserConfig(userId);
        String modelName = thirdPartyLlmClient.effectiveModelName(userId);
        AtomicBoolean streamedAnyChunk = new AtomicBoolean(false);
        java.util.function.Consumer<String> trackedChunk = delta -> {
            if (delta != null && !delta.isEmpty()) {
                streamedAnyChunk.set(true);
                onChunk.accept(delta);
            }
        };
        String answer = isHomeworkStyle(request.answerStyle())
            ? thirdPartyLlmClient.answerStream(userId, llmQuestion, excerpts, images, trackedChunk, generalChat, request.answerStyle())
            : thirdPartyLlmClient.answerStream(userId, llmQuestion, excerpts, images, trackedChunk, generalChat, "STUDY");
        if (answer.isBlank()) {
            answer = generalChat
                ? buildGeneralFallbackAnswer(request.question())
                : buildCleanAnswer(request.question(), selectedChunks, request.answerStyle());
            modelName = "local-rag-demo";
            customModel = false;
        }
        if (!streamedAnyChunk.get() && !answer.isBlank()) {
            streamAnswerInSmallChunks(answer, onChunk);
        }

        String decoratedAnswer = generalChat
            ? decorateGeneralAnswer(answer)
            : decorateAnswer(request, answer, selectedChunks);

        TokenUsage usage = estimateUsage(llmQuestion, answer);
        RagQuestionEntity savedQuestion = saveStreamResult(userId, request, decoratedAnswer, selectedChunks, modelName, customModel, usage);
        recordUsageLog(userId, "RAG_CHAT_STREAM", savedQuestion.getId(), request, new LlmCompletion(
            decoratedAnswer,
            modelName,
            usage.promptTokens(),
            usage.completionTokens(),
            usage.totalTokens(),
            customModel
        ));

        List<RagSourceResponse> sources = selectedChunks.stream()
            .map(this::toSourceResponse)
            .toList();

        return new RagStreamResult(savedQuestion.getId(), savedQuestion.getConversationId(), decoratedAnswer, sources);
    }

    private RagStreamResult streamModelIdentity(
        long userId,
        ChatRequest request,
        java.util.function.Consumer<String> onChunk
    ) {
        LlmCompletion completion = thirdPartyLlmClient.currentModelCompletion(userId);
        onChunk.accept(completion.content());
        TokenUsage usage = estimateUsage(request.question(), completion.content());
        completion = new LlmCompletion(
            completion.content(),
            completion.modelName(),
            usage.promptTokens(),
            usage.completionTokens(),
            usage.totalTokens(),
            completion.customModel()
        );
        RagQuestionEntity savedQuestion = saveStreamResult(
            userId,
            request,
            completion.content(),
            List.of(),
            completion.modelName(),
            completion.customModel(),
            usage
        );
        recordUsageLog(userId, "RAG_CHAT_STREAM", savedQuestion.getId(), request, completion);
        return new RagStreamResult(savedQuestion.getId(), savedQuestion.getConversationId(), completion.content(), List.of());
    }

    private void streamAnswerInSmallChunks(String answer, java.util.function.Consumer<String> onChunk) {
        String value = answer == null ? "" : answer;
        if (value.isBlank()) {
            return;
        }
        int index = 0;
        while (index < value.length()) {
            int next = nextStreamChunkEnd(value, index);
            onChunk.accept(value.substring(index, next));
            index = next;
            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private int nextStreamChunkEnd(String value, int start) {
        int maxEnd = Math.min(value.length(), start + 18);
        for (int i = start + 1; i <= maxEnd; i++) {
            char c = value.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '，' || c == '；' || c == '！' || c == '？' || c == '.' || c == ',' || c == ';') {
                return i;
            }
        }
        return maxEnd;
    }

    @Transactional(readOnly = true)
    public RagUsageResponse usage(long userId) {
        boolean unlimited = isAdminUser(userId) || thirdPartyLlmClient.hasActiveUserConfig(userId);
        long usedToday = countUserQuestionsToday(userId);
        Long remainingToday = unlimited ? null : Math.max(0, USER_DAILY_CHAT_LIMIT - usedToday);
        return new RagUsageResponse(USER_DAILY_CHAT_LIMIT, usedToday, remainingToday, unlimited);
    }

    private void ensureChatUsageAvailable(long userId) {
        if (isAdminUser(userId) || thirdPartyLlmClient.hasActiveUserConfig(userId)) {
            return;
        }
        long usedToday = countUserQuestionsToday(userId);
        if (usedToday >= USER_DAILY_CHAT_LIMIT) {
            throw new BusinessException(429, "今日问答次数已用完，请明天再试");
        }
    }

    private long countUserQuestionsToday(long userId) {
        return ragQuestionRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, LocalDateTime.now().toLocalDate().atStartOfDay());
    }

    private boolean isAdminUser(long userId) {
        return userRepository.findById(userId)
            .map(user -> user.getRole() == UserRole.ADMIN)
            .orElse(false);
    }

    private List<LlmImage> userImages(ChatRequest request) {
        if (request.images() == null || request.images().isEmpty()) {
            return List.of();
        }
        if (request.images().size() > MAX_USER_IMAGES_PER_REQUEST) {
            throw new BusinessException(400, "一次最多上传 4 张图片");
        }
        List<LlmImage> images = new ArrayList<>();
        for (ChatImage image : request.images()) {
            if (image == null) {
                continue;
            }
            String mediaType = normalizeImageMediaType(image.resolvedMediaType());
            String base64Data = normalizeImageBase64(image);
            if (base64Data.isBlank()) {
                continue;
            }
            if (base64Data.length() > MAX_USER_IMAGE_BASE64_CHARS) {
                throw new BusinessException(413, "图片过大，请压缩后再上传");
            }
            images.add(new LlmImage(base64Data, mediaType));
        }
        return images;
    }

    private String normalizeImageMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "image/jpeg";
        }
        if (!List.of("image/jpeg", "image/png", "image/webp", "image/gif").contains(normalized)) {
            throw new BusinessException(400, "仅支持 JPG、PNG、WEBP、GIF 图片");
        }
        return normalized;
    }

    private String normalizeImageBase64(ChatImage image) {
        String value = image.base64Data();
        if ((value == null || value.isBlank()) && image.dataUrl() != null) {
            value = image.dataUrl().trim();
        }
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("data:")) {
            int commaIndex = trimmed.indexOf(',');
            if (commaIndex < 0) {
                throw new BusinessException(400, "图片数据格式不正确");
            }
            trimmed = trimmed.substring(commaIndex + 1);
        }
        if (!trimmed.matches("^[A-Za-z0-9+/=\\r\\n]+$")) {
            throw new BusinessException(400, "图片数据格式不正确");
        }
        return trimmed.replaceAll("\\s+", "");
    }

    private List<ScoredChunk> selectContextChunks(long userId, ChatRequest request, boolean generalChat) {
        if (generalChat || (request.selectedText() != null && !request.selectedText().isBlank())) {
            return List.of();
        }
        String retrievalQuestion = rewriteQuestionForRetrieval(request.question(), request.history());
        List<ScoredChunk> keywordChunks = findKeywordChunks(userId, retrievalQuestion, request.materialId());
        if (!keywordChunks.isEmpty()) {
            return keywordChunks;
        }
        List<ScoredChunk> currentPageChunks = findCurrentPageChunks(userId, request);
        if (request.chunkId() == null) {
            if (!currentPageChunks.isEmpty()) {
                if (isLocalContextQuestion(request.question())) {
                    return currentPageChunks.stream().limit(8).toList();
                }
                List<ScoredChunk> selected = new ArrayList<>();
                Set<Long> seenChunkIds = new HashSet<>();
                appendUniqueChunks(selected, seenChunkIds, currentPageChunks);
                appendUniqueChunks(selected, seenChunkIds, selectVectorOrKeywordChunks(userId, retrievalQuestion, request.materialId()));
                return limitContextChunks(selected);
            }
            List<ScoredChunk> topChunks = selectVectorOrKeywordChunks(userId, retrievalQuestion, request.materialId());
            if (!topChunks.isEmpty()) {
                return topChunks;
            }
            if (isMaterialOverviewQuestion(request.question())) {
                return materialOverviewChunks(userId, request.materialId());
            }
            return topChunks;
        }

        List<ScoredChunk> currentChunks = findChunksById(userId, request);
        if (currentChunks.isEmpty()) {
            List<ScoredChunk> topChunks = selectVectorOrKeywordChunks(userId, retrievalQuestion, request.materialId());
            if (!topChunks.isEmpty()) {
                return topChunks;
            }
            if (isMaterialOverviewQuestion(request.question())) {
                return materialOverviewChunks(userId, request.materialId());
            }
            return topChunks;
        }

        List<ScoredChunk> selected = new ArrayList<>();
        Set<Long> seenChunkIds = new HashSet<>();
        appendUniqueChunks(selected, seenChunkIds, currentChunks);
        appendUniqueChunks(selected, seenChunkIds, !currentPageChunks.isEmpty() ? currentPageChunks : currentPageChunks(currentChunks.get(0)));
        appendUniqueChunks(selected, seenChunkIds, currentSectionChunks(currentChunks.get(0)));
        if (isLocalContextQuestion(request.question())) {
            return selected.stream().limit(8).toList();
        }
        appendUniqueChunks(
            selected,
            seenChunkIds,
            selectVectorOrKeywordChunks(userId, retrievalQuestion, currentChunks.get(0).material().getId())
        );
        return limitContextChunks(selected);
    }

    private List<ScoredChunk> selectVectorOrKeywordChunks(long userId, String question, Long materialId) {
        List<LearningMaterialEntity> materials = retrievalScopeMaterials(userId, materialId);
        String cacheKey = retrievalCacheKey(userId, materialId, question, materials);
        List<ScoredChunk> cachedChunks = cachedRetrievalChunks(cacheKey, userId);
        if (cachedChunks != null) {
            return cachedChunks;
        }

        List<ScoredChunk> vectorChunks = new ArrayList<>();
        List<ScoredChunk> bm25Chunks = new ArrayList<>();
        List<ScoredChunk> summarySeedChunks = findSummarySeedChunks(userId, question, materialId);
        List<String> queries = expandedRetrievalQueries(question);
        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            double weight = i == 0 ? 1.0 : 0.82;
            vectorChunks.addAll(weightedChunks(findVectorScoredChunks(userId, query, materialId), weight));
            bm25Chunks.addAll(weightedChunks(findScoredChunks(userId, query, materialId), weight));
        }
        hydeRetrievalQuery(question).ifPresent(hydeQuery ->
            vectorChunks.addAll(weightedChunks(
                findVectorScoredChunks(userId, hydeQuery, materialId),
                queryExpansionProperties.hydeWeight()
            ))
        );
        List<ScoredChunk> selected = selectTopChunks(fuseAndRerankChunks(question, vectorChunks, bm25Chunks, summarySeedChunks));
        rememberRetrievalResult(cacheKey, selected);
        return selected;
    }

    private List<ScoredChunk> findSummarySeedChunks(long userId, String question, Long materialId) {
        List<String> queryTerms = significantQueryTerms(question);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<LearningMaterialEntity> materials = retrievalScopeMaterials(userId, materialId);
        List<ScoredChunk> seeds = new ArrayList<>();
        for (LearningMaterialEntity material : materials) {
            if (material.getParseStatus() != MaterialParseStatus.SUCCESS) {
                continue;
            }
            List<MaterialSummaryEntity> summaries = materialSummaryRepository.findByMaterialIdAndUserIdOrderByCreatedAtDesc(material.getId(), userId);
            double summaryScore = summaries.stream()
                .map(MaterialSummaryEntity::getSummaryText)
                .mapToDouble(summary -> queryTermCoverage(summary, queryTerms))
                .max()
                .orElse(0.0);
            if (summaryScore <= 0.0) {
                continue;
            }
            materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId()).stream()
                .filter(chunk -> chunk.getChunkText() != null && !cleanExcerptText(chunk.getChunkText()).isBlank())
                .limit(3)
                .map(chunk -> new ScoredChunk(material, chunk, 0.75 + Math.min(0.2, summaryScore * 0.2), queryTerms))
                .forEach(seeds::add);
        }
        return seeds;
    }

    private List<String> expandedRetrievalQueries(String question) {
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isBlank() || !queryExpansionProperties.enabled()) {
            return normalizedQuestion.isBlank() ? List.of() : List.of(normalizedQuestion);
        }
        List<String> queries = new ArrayList<>();
        queries.add(normalizedQuestion);
        thirdPartyLlmClient.expandQuery(normalizedQuestion)
            .orElseGet(() -> queryExpansionProperties.localFallback() ? localQueryExpansions(normalizedQuestion) : List.of())
            .stream()
            .map(query -> query == null ? "" : query.trim())
            .filter(query -> !query.isBlank())
            .filter(query -> queries.stream().noneMatch(existing -> normalizeForTermMatch(existing).equals(normalizeForTermMatch(query))))
            .limit(Math.max(0, queryExpansionProperties.maxQueries() - 1))
            .forEach(queries::add);
        return queries;
    }

    private Optional<String> hydeRetrievalQuery(String question) {
        if (!queryExpansionProperties.enabled() || !queryExpansionProperties.hydeEnabled()) {
            return Optional.empty();
        }
        String normalizedQuestion = question == null ? "" : question.trim();
        if (normalizedQuestion.isBlank()) {
            return Optional.empty();
        }
        return thirdPartyLlmClient.generateHydeAnswer(normalizedQuestion)
            .map(String::trim)
            .filter(hyde -> !hyde.isBlank())
            .filter(hyde -> !normalizeForTermMatch(hyde).equals(normalizeForTermMatch(normalizedQuestion)));
    }

    private List<String> localQueryExpansions(String question) {
        List<String> expansions = new ArrayList<>();
        KeywordQuery keywordQuery = extractKeywordQuery(question);
        if (keywordQuery != null && !keywordQuery.terms().isEmpty()) {
            String terms = String.join(" ", keywordQuery.terms());
            expansions.add(terms + " 定义 原理 作用");
            expansions.add(terms + " 优点 缺点 特点");
        }
        String normalized = normalizeForTermMatch(question);
        if (containsAny(normalized, "数据库", "查询", "查找", "检索") && containsAny(normalized, "快", "加速", "速度", "性能", "效率")) {
            expansions.add("索引 数据库 查询 查找 过滤 速度 性能");
        }
        if (containsAny(normalized, "为什么", "原因", "原理", "怎么", "如何")) {
            expansions.add(question + " 原因 原理 工作过程");
        }
        if (containsAny(normalized, "优缺点", "优点", "缺点", "特点", "优势", "劣势")) {
            expansions.add(question + " 优点 缺点 限制 适用场景");
        }
        return expansions;
    }

    private List<ScoredChunk> weightedChunks(List<ScoredChunk> chunks, double weight) {
        if (chunks.isEmpty() || weight == 1.0) {
            return chunks;
        }
        return chunks.stream()
            .map(chunk -> new ScoredChunk(chunk.material(), chunk.chunk(), chunk.score() * weight, chunk.highlightTerms()))
            .toList();
    }

    private List<LearningMaterialEntity> retrievalScopeMaterials(long userId, Long materialId) {
        return materialId == null
            ? learningMaterialRepository.findByOwnerIdOrderByCreatedAtDesc(userId).stream()
                .filter(material -> material.getParseStatus() == MaterialParseStatus.SUCCESS)
                .toList()
            : learningMaterialRepository.findByIdAndOwnerId(materialId, userId).stream()
                .filter(material -> material.getParseStatus() == MaterialParseStatus.SUCCESS)
                .toList();
    }

    private String retrievalCacheKey(long userId, Long materialId, String question, List<LearningMaterialEntity> materials) {
        StringBuilder key = new StringBuilder(materialId == null ? "user:" + userId : "material:" + materialId);
        key.append("|q=").append(sha256(normalizeForTermMatch(question)));
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

    private List<ScoredChunk> cachedRetrievalChunks(String cacheKey, long userId) {
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
            if (material == null || material.getParseStatus() != MaterialParseStatus.SUCCESS) {
                retrievalResultCache.remove(cacheKey);
                return null;
            }
            chunks.add(new ScoredChunk(material, chunk, cachedChunk.score(), cachedChunk.highlightTerms()));
        }
        return chunks;
    }

    private void rememberRetrievalResult(String cacheKey, List<ScoredChunk> chunks) {
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

    private List<ScoredChunk> fuseAndRerankChunks(
        String question,
        List<ScoredChunk> vectorChunks,
        List<ScoredChunk> bm25Chunks,
        List<ScoredChunk> summarySeedChunks
    ) {
        Map<Long, HybridCandidate> candidates = new LinkedHashMap<>();
        for (ScoredChunk chunk : vectorChunks) {
            HybridCandidate candidate = candidates.computeIfAbsent(chunk.chunk().getId(), ignored -> new HybridCandidate(chunk));
            candidate.vectorScore = Math.max(candidate.vectorScore, chunk.score());
            candidate.highlightTerms = chunk.highlightTerms();
        }
        for (ScoredChunk chunk : bm25Chunks) {
            HybridCandidate candidate = candidates.computeIfAbsent(chunk.chunk().getId(), ignored -> new HybridCandidate(chunk));
            candidate.bm25Score = Math.max(candidate.bm25Score, chunk.score());
            if (candidate.highlightTerms.isEmpty()) {
                candidate.highlightTerms = chunk.highlightTerms();
            }
        }
        for (ScoredChunk chunk : summarySeedChunks) {
            HybridCandidate candidate = candidates.computeIfAbsent(chunk.chunk().getId(), ignored -> new HybridCandidate(chunk));
            candidate.summaryScore = Math.max(candidate.summaryScore, chunk.score());
            if (candidate.highlightTerms.isEmpty()) {
                candidate.highlightTerms = chunk.highlightTerms();
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        double maxVector = candidates.values().stream().mapToDouble(candidate -> candidate.vectorScore).max().orElse(0.0);
        double maxBm25 = candidates.values().stream().mapToDouble(candidate -> candidate.bm25Score).max().orElse(0.0);
        double maxSummary = candidates.values().stream().mapToDouble(candidate -> candidate.summaryScore).max().orElse(0.0);
        List<String> queryTerms = significantQueryTerms(question);

        List<ScoredChunk> fusedChunks = candidates.values().stream()
            .map(candidate -> {
                double vectorScore = maxVector <= 0.0 ? 0.0 : candidate.vectorScore / maxVector;
                double bm25Score = maxBm25 <= 0.0 ? 0.0 : candidate.bm25Score / maxBm25;
                double summaryScore = maxSummary <= 0.0 ? 0.0 : candidate.summaryScore / maxSummary;
                double termScore = queryTermCoverage(retrievalText(candidate.chunk), queryTerms);
                double fusedScore = (0.48 * vectorScore) + (0.30 * bm25Score) + (0.12 * summaryScore) + (0.10 * termScore);
                return new ScoredChunk(candidate.material, candidate.chunk, fusedScore, candidate.highlightTerms);
            })
            .filter(chunk -> chunk.score() > 0.0)
            .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
            .toList();
        return rerankChunks(question, fusedChunks);
    }

    private List<ScoredChunk> rerankChunks(String question, List<ScoredChunk> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }
        List<RerankCandidate> candidates = chunks.stream()
            .map(chunk -> new RerankCandidate(chunk.chunk().getId(), retrievalText(chunk.chunk()), chunk.score()))
            .toList();
        List<RerankedCandidate> rerankedCandidates = rerankerClient.rerank(question, candidates);
        if (rerankedCandidates.isEmpty()) {
            return chunks;
        }

        Map<Long, ScoredChunk> byChunkId = chunks.stream()
            .collect(Collectors.toMap(
                chunk -> chunk.chunk().getId(),
                chunk -> chunk,
                (first, ignored) -> first,
                LinkedHashMap::new
            ));
        List<ScoredChunk> reranked = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (RerankedCandidate candidate : rerankedCandidates) {
            ScoredChunk chunk = byChunkId.get(candidate.id());
            if (chunk == null || !seen.add(candidate.id())) {
                continue;
            }
            reranked.add(new ScoredChunk(chunk.material(), chunk.chunk(), candidate.score(), chunk.highlightTerms()));
        }
        for (ScoredChunk chunk : chunks) {
            if (seen.add(chunk.chunk().getId())) {
                reranked.add(chunk);
            }
        }
        return reranked;
    }

    private double queryTermCoverage(String text, List<String> queryTerms) {
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        String normalizedText = normalizeForTermMatch(text);
        long matches = queryTerms.stream().filter(normalizedText::contains).count();
        return (double) matches / queryTerms.size();
    }

    private List<String> significantQueryTerms(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        KeywordQuery keywordQuery = extractKeywordQuery(question);
        if (keywordQuery != null && !keywordQuery.terms().isEmpty()) {
            return keywordQuery.terms().stream()
                .map(this::normalizeForTermMatch)
                .filter(term -> term.length() >= 2)
                .distinct()
                .toList();
        }
        List<String> terms = new ArrayList<>();
        Matcher matcher = Pattern.compile("[\\p{IsHan}a-zA-Z0-9+#./-]{2,}").matcher(question);
        while (matcher.find()) {
            String term = cleanKeywordTerm(matcher.group());
            if (term == null) {
                continue;
            }
            String normalized = normalizeForTermMatch(term);
            if (normalized.length() >= 2 && !isWeakQueryTerm(normalized)) {
                terms.add(normalized);
            }
        }
        return terms.stream().distinct().limit(6).toList();
    }

    private boolean isWeakQueryTerm(String term) {
        return Set.of(
            "什么", "怎么", "为什么", "哪里", "哪个", "哪些", "一下", "介绍", "解释", "区别",
            "what", "how", "why", "where", "which", "does", "this", "that", "with"
        ).contains(term);
    }

    private List<ScoredChunk> findTermDefinitionChunks(long userId, String question, Long materialId) {
        String term = extractDefinitionTerm(question);
        if (term == null || term.isBlank() || materialId == null) {
            return List.of();
        }
        return findKeywordChunks(userId, new KeywordQuery(List.of(term), KeywordIntent.DEFINITION), materialId);
    }

    private List<ScoredChunk> findKeywordChunks(long userId, String question, Long materialId) {
        KeywordQuery query = extractKeywordQuery(question);
        if (query == null) {
            return List.of();
        }
        return findKeywordChunks(userId, query, materialId);
    }

    private List<ScoredChunk> findKeywordChunks(long userId, KeywordQuery query, Long materialId) {
        if (query.terms().isEmpty() || materialId == null) {
            return List.of();
        }
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElse(null);
        if (material == null || material.getParseStatus() != MaterialParseStatus.SUCCESS) {
            return List.of();
        }
        List<String> normalizedTerms = query.terms().stream()
            .map(this::normalizeForTermMatch)
            .filter(term -> term.length() >= 2)
            .distinct()
            .toList();
        if (normalizedTerms.isEmpty()) {
            return List.of();
        }
        return materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId()).stream()
            .filter(chunk -> containsAnyTerm(retrievalText(chunk), normalizedTerms))
            .map(chunk -> new ScoredChunk(
                material,
                chunk,
                keywordScore(retrievalText(chunk), normalizedTerms, query.intent()),
                query.terms()
            ))
            .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
            .limit(5)
            .toList();
    }

    private KeywordQuery extractKeywordQuery(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String trimmed = question.trim();
        KeywordQuery cnDefinition = extractChineseDefinitionQuery(trimmed);
        if (cnDefinition != null) {
            return cnDefinition;
        }
        KeywordQuery comparison = extractComparisonQuery(trimmed);
        if (comparison != null) {
            return comparison;
        }
        KeywordQuery occurrence = extractOccurrenceQuery(trimmed);
        if (occurrence != null) {
            return occurrence;
        }
        KeywordQuery function = extractFunctionQuery(trimmed);
        if (function != null) {
            return function;
        }
        KeywordQuery aspect = extractAspectQuery(trimmed);
        if (aspect != null) {
            return aspect;
        }
        KeywordQuery openEnded = extractOpenEndedTopicQuery(trimmed);
        if (openEnded != null) {
            return openEnded;
        }
        String definitionTerm = extractDefinitionTerm(trimmed);
        if (definitionTerm != null) {
            return new KeywordQuery(List.of(definitionTerm), KeywordIntent.DEFINITION);
        }
        return null;
    }

    private KeywordQuery extractChineseDefinitionQuery(String question) {
        Matcher prefix = Pattern.compile("(?:\\u4ec0\\u4e48\\u662f|\\u4f55\\u4e3a|\\u89e3\\u91ca\\u4e00\\u4e0b|\\u89e3\\u91ca|\\u5b9a\\u4e49)\\s*(.{2,80}?)(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (prefix.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, prefix.group(1));
        }
        Matcher suffix = Pattern.compile("(.{2,80}?)\\s*(?:\\u7684\\u5b9a\\u4e49|\\u7684\\u542b\\u4e49|\\u7684\\u6982\\u5ff5|\\u662f\\u4ec0\\u4e48\\u610f\\u601d|\\u6307\\u4ec0\\u4e48|\\u600e\\u4e48\\u7406\\u89e3)(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (suffix.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, suffix.group(1));
        }
        return null;
    }

    private KeywordQuery extractComparisonQuery(String question) {
        Matcher cnBetween = Pattern.compile("(.{2,60}?)\\s*(?:\\u548c|\\u4e0e)\\s*(.{2,60}?)\\s*(?:\\u6709\\u4ec0\\u4e48\\u533a\\u522b|\\u7684\\u533a\\u522b|\\u7684\\u5173\\u7cfb)").matcher(question);
        if (cnBetween.find()) {
            return keywordQuery(KeywordIntent.COMPARISON, cnBetween.group(1), cnBetween.group(2));
        }
        Matcher cnChoice = Pattern.compile("(.{2,60}?)\\s*(?:\\u548c|\\u4e0e)\\s*(.{2,60}?)\\s*(?:\\u54ea\\u4e2a\\u66f4\\u597d|\\u54ea\\u4e2a\\u597d|\\u5982\\u4f55\\u9009\\u62e9|\\u600e\\u4e48\\u9009)").matcher(question);
        if (cnChoice.find()) {
            return keywordQuery(KeywordIntent.COMPARISON, cnChoice.group(1), cnChoice.group(2));
        }
        Matcher between = Pattern.compile("(?i)difference\\s+between\\s+(.{2,60}?)\\s+and\\s+(.{2,60})(?:[?.!]|$)").matcher(question);
        if (between.find()) {
            return keywordQuery(KeywordIntent.COMPARISON, between.group(1), between.group(2));
        }
        Matcher versus = Pattern.compile("(?i)(.{2,60}?)\\s+(?:vs|versus)\\s+(.{2,60})(?:[?.!]|$)").matcher(question);
        if (versus.find()) {
            return keywordQuery(KeywordIntent.COMPARISON, versus.group(1), versus.group(2));
        }
        return null;
    }

    private KeywordQuery extractOccurrenceQuery(String question) {
        Matcher cnWhere = Pattern.compile("(.{2,80}?)\\s*(?:\\u5728\\u54ea\\u91cc\\u63d0\\u5230|\\u54ea\\u91cc\\u63d0\\u5230|\\u51fa\\u73b0\\u5728\\u54ea\\u91cc|\\u51fa\\u73b0\\u5728\\u54ea|\\u51fa\\u73b0\\u8fc7\\u54ea\\u4e9b)").matcher(question);
        if (cnWhere.find()) {
            return keywordQuery(KeywordIntent.OCCURRENCE, cnWhere.group(1));
        }
        Matcher mentioned = Pattern.compile("(?i)where\\s+(?:is|are)\\s+(.{2,80}?)\\s+mentioned").matcher(question);
        if (mentioned.find()) {
            return keywordQuery(KeywordIntent.OCCURRENCE, mentioned.group(1));
        }
        Matcher appear = Pattern.compile("(?i)where\\s+does\\s+(.{2,80}?)\\s+appear").matcher(question);
        if (appear.find()) {
            return keywordQuery(KeywordIntent.OCCURRENCE, appear.group(1));
        }
        return null;
    }

    private KeywordQuery extractFunctionQuery(String question) {
        Matcher cnFunction = Pattern.compile("(.{2,80}?)\\s*(?:\\u6709\\u4ec0\\u4e48\\u7528|\\u7684\\u4f5c\\u7528|\\u7684\\u7528\\u9014|\\u4e3b\\u8981\\u4f5c\\u7528|\\u7528\\u6765\\u505a\\u4ec0\\u4e48)").matcher(question);
        if (cnFunction.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, cnFunction.group(1));
        }
        Matcher usedFor = Pattern.compile("(?i)what\\s+(?:is|are)\\s+(.{2,80}?)\\s+(?:used\\s+for|for)(?:[?.!]|$)").matcher(question);
        if (usedFor.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, usedFor.group(1));
        }
        Matcher of = Pattern.compile("(?i)(?:role|function|purpose|use)\\s+of\\s+(.{2,80})(?:[?.!]|$)").matcher(question);
        if (of.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, of.group(1));
        }
        return null;
    }

    private KeywordQuery extractAspectQuery(String question) {
        Matcher cnAspect = Pattern.compile("(.{2,80}?)\\s*(?:\\u7684)?(?:\\u4f18\\u7f3a\\u70b9|\\u4f18\\u70b9|\\u7f3a\\u70b9|\\u7279\\u70b9|\\u7279\\u5f81|\\u4f18\\u52bf|\\u52a3\\u52bf|\\u5c40\\u9650|\\u98ce\\u9669)(?:\\u662f\\u4ec0\\u4e48|\\u6709\\u54ea\\u4e9b|\\u5982\\u4f55|\\u600e\\u4e48\\u6837|\\u5462)?(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (cnAspect.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, cnAspect.group(1));
        }
        Matcher englishAspect = Pattern.compile("(?i)(?:advantages|disadvantages|pros|cons|strengths|weaknesses|features|limitations|risks)\\s+of\\s+(.{2,80})(?:[?.!]|$)").matcher(question);
        if (englishAspect.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, englishAspect.group(1));
        }
        return null;
    }

    private KeywordQuery keywordQuery(KeywordIntent intent, String... rawTerms) {
        List<String> terms = new ArrayList<>();
        for (String rawTerm : rawTerms) {
            String term = cleanKeywordTerm(rawTerm);
            if (term != null && !term.isBlank()) {
                terms.add(term);
            }
        }
        terms = terms.stream().distinct().limit(2).toList();
        return terms.isEmpty() ? null : new KeywordQuery(terms, intent);
    }

    private String extractDefinitionTerm(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher matcher = TERM_DEFINITION_PATTERN.matcher(question.trim());
        if (!matcher.find()) {
            return null;
        }
        String term = matcher.group(1).trim()
            .replaceAll("^(一下|这个|该|所谓|资料里的|文中的|课件里的|书中的)\\s*", "")
            .replaceAll("\\s*(是什么|是啥|的定义|的含义|的概念|是什么意思|指什么|怎么理解)$", "")
            .trim();
        return term.isBlank() ? null : term;
    }

    private boolean containsTerm(String text, String normalizedTerm) {
        return normalizeForTermMatch(text).contains(normalizedTerm);
    }

    private double definitionScore(String text, String normalizedTerm) {
        String normalizedText = normalizeForTermMatch(text);
        int index = normalizedText.indexOf(normalizedTerm);
        double score = index < 0 ? 0.0 : 20.0;
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (lower.contains("定义") || lower.contains("概念") || lower.contains("含义")
            || lower.contains("是指") || lower.contains("指的是") || lower.contains("refers to")
            || lower.contains("defined as") || lower.contains("definition")) {
            score += 8.0;
        }
        if (index >= 0) {
            score += Math.max(0.0, 5.0 - index / 80.0);
        }
        return score;
    }

    private String cleanKeywordTerm(String value) {
        if (value == null) {
            return null;
        }
        String term = value.trim()
            .replaceAll("^[\"'`\\s]+|[\"'`\\s]+$", "")
            .replaceAll("(?i)^(the|a|an|this|current|material|document)\\s+", "")
            .replaceAll("^(\\u8d44\\u6599\\u91cc\\u7684|\\u6587\\u4e2d\\u7684|\\u8bfe\\u4ef6\\u91cc\\u7684|\\u4e66\\u4e2d\\u7684|\\u8fd9\\u4e2a|\\u8be5|\\u6240\\u8c13)\\s*", "")
            .replaceAll("(?i)\\s+(definition|meaning|concept|role|function|purpose|use)$", "")
            .replaceAll("\\s*(\\u7684\\u5b9a\\u4e49|\\u7684\\u542b\\u4e49|\\u7684\\u6982\\u5ff5|\\u7684\\u4f5c\\u7528|\\u7684\\u7528\\u9014|\\u7684\\u533a\\u522b|\\u7684\\u5173\\u7cfb)$", "")
            .replaceAll("[?.!,;:]+$", "")
            .replaceAll("[\\uff1f\\uff01\\u3002\\uff0c\\uff1b\\uff1a]+$", "")
            .trim();
        return term.isBlank() ? null : term;
    }

    private boolean containsAnyTerm(String text, List<String> normalizedTerms) {
        String normalizedText = normalizeForTermMatch(text);
        return normalizedTerms.stream().anyMatch(normalizedText::contains);
    }

    private String retrievalText(MaterialChunkEntity chunk) {
        if (chunk == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendRetrievalPart(builder, chunk.getChunkText());
        appendRetrievalPart(builder, chunk.getHierarchyPath());
        appendRetrievalPart(builder, chunk.getSectionTitle());
        appendRetrievalPart(builder, chunk.getSummary());
        appendRetrievalPart(builder, chunk.getKeywords());
        return builder.toString();
    }

    private void appendRetrievalPart(StringBuilder builder, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(part.trim());
    }

    private double keywordScore(String text, List<String> normalizedTerms, KeywordIntent intent) {
        String normalizedText = normalizeForTermMatch(text);
        double score = 0.0;
        for (String normalizedTerm : normalizedTerms) {
            int index = normalizedText.indexOf(normalizedTerm);
            if (index < 0) {
                continue;
            }
            score += 20.0;
            score += Math.max(0.0, 5.0 - index / 80.0);
        }
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (intent == KeywordIntent.DEFINITION && containsAny(lower, "refers to", "defined as", "definition", "means", "\u5b9a\u4e49", "\u6982\u5ff5", "\u542b\u4e49", "\u662f\u6307", "\u6307\u7684\u662f")) {
            score += 10.0;
        } else if (intent == KeywordIntent.FUNCTION && containsAny(lower, "used for", "used to", "function", "purpose", "role", "helps", "allows", "enables", "\u4f5c\u7528", "\u7528\u9014", "\u7528\u4e8e", "\u7528\u6765", "\u5e2e\u52a9")) {
            score += 10.0;
        } else if (intent == KeywordIntent.COMPARISON) {
            if (normalizedTerms.stream().allMatch(normalizedText::contains)) {
                score += 12.0;
            }
            if (containsAny(lower, "difference", "different", "similar", "relationship", "compared", "versus", "\u533a\u522b", "\u4e0d\u540c", "\u76f8\u540c", "\u5173\u7cfb", "\u76f8\u5173")) {
                score += 10.0;
            }
        } else if (intent == KeywordIntent.OCCURRENCE) {
            score += 4.0;
        }
        return score;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeForTermMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？；：“”‘’（）《》、]+", "");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private boolean isLocalContextQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return LOCAL_CONTEXT_QUESTION_PATTERN.matcher(question).find();
    }

    private boolean isMaterialOverviewQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return MATERIAL_OVERVIEW_QUESTION_PATTERN.matcher(question).find();
    }

    private List<ScoredChunk> materialOverviewChunks(long userId, Long materialId) {
        if (materialId == null) {
            return List.of();
        }
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElse(null);
        if (material == null || material.getParseStatus() != MaterialParseStatus.SUCCESS) {
            return List.of();
        }
        List<MaterialChunkEntity> chunks = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId()).stream()
            .filter(chunk -> chunk.getChunkText() != null && !cleanExcerptText(chunk.getChunkText()).isBlank())
            .limit(5)
            .toList();
        List<ScoredChunk> selected = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            selected.add(new ScoredChunk(material, chunks.get(i), Math.max(0.7, 0.95 - i * 0.05)));
        }
        return selected;
    }

    private List<ScoredChunk> currentPageChunks(ScoredChunk currentChunk) {
        Integer pageNo = currentChunk.chunk().getPageNo();
        if (pageNo == null) {
            return List.of();
        }
        return materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(currentChunk.material().getId()).stream()
            .filter(chunk -> pageNo.equals(chunk.getPageNo()))
            .map(chunk -> new ScoredChunk(currentChunk.material(), chunk, chunk.getId().equals(currentChunk.chunk().getId()) ? 1.0 : 0.95))
            .toList();
    }

    private List<ScoredChunk> findCurrentPageChunks(long userId, ChatRequest request) {
        if (request.materialId() == null) {
            return List.of();
        }
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(request.materialId(), userId)
            .orElse(null);
        if (material == null || material.getParseStatus() != MaterialParseStatus.SUCCESS) {
            return List.of();
        }
        List<MaterialChunkEntity> allChunks = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId());
        if (request.currentPageChunkIds() != null && !request.currentPageChunkIds().isEmpty()) {
            Set<Long> pageChunkIds = request.currentPageChunkIds().stream()
                .filter(id -> id != null)
                .collect(Collectors.toSet());
            if (!pageChunkIds.isEmpty()) {
                return allChunks.stream()
                    .filter(chunk -> chunk.getId() != null && pageChunkIds.contains(chunk.getId()))
                    .map(chunk -> new ScoredChunk(material, chunk, chunk.getId().equals(request.chunkId()) ? 1.0 : 0.96))
                    .toList();
            }
        }
        if (request.currentPageNo() == null) {
            return List.of();
        }
        return allChunks.stream()
            .filter(chunk -> request.currentPageNo().equals(chunk.getPageNo()))
            .map(chunk -> new ScoredChunk(material, chunk, chunk.getId().equals(request.chunkId()) ? 1.0 : 0.96))
            .toList();
    }

    private List<ScoredChunk> currentSectionChunks(ScoredChunk currentChunk) {
        String sectionTitle = currentChunk.chunk().getSectionTitle();
        if (sectionTitle == null || sectionTitle.isBlank()) {
            return List.of();
        }
        return materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(currentChunk.material().getId()).stream()
            .filter(chunk -> sectionTitle.equals(chunk.getSectionTitle()))
            .map(chunk -> new ScoredChunk(currentChunk.material(), chunk, chunk.getId().equals(currentChunk.chunk().getId()) ? 1.0 : 0.9))
            .toList();
    }

    private void appendUniqueChunks(List<ScoredChunk> target, Set<Long> seenChunkIds, List<ScoredChunk> chunks) {
        for (ScoredChunk chunk : chunks) {
            if (chunk.chunk().getId() != null && seenChunkIds.add(chunk.chunk().getId())) {
                target.add(chunk);
            }
        }
    }

    private List<String> buildExcerpts(ChatRequest request, List<ScoredChunk> selectedChunks) {
        if (request.selectedText() != null && !request.selectedText().isBlank()) {
            return List.of("[用户选中内容]\n原文：" + request.selectedText().trim());
        }
        if (request.chunkId() == null) {
            return selectedChunks.stream().map(this::sourceContext).toList();
        }
        List<String> excerpts = new ArrayList<>();
        for (int i = 0; i < selectedChunks.size(); i++) {
            String label = i == 0 ? "[当前阅读位置，优先依据]" : "[同页或同书补充依据]";
            excerpts.add(sourceContext(selectedChunks.get(i), label));
        }
        return excerpts;
    }

    private RagQuestionEntity saveStreamResult(
        long userId,
        ChatRequest request,
        String answer,
        List<ScoredChunk> chunks,
        String modelName,
        boolean customModel,
        TokenUsage usage
    ) {
        try {
            RagQuestionEntity question = new RagQuestionEntity();
            question.setUserId(userId);
            question.setConversationId(resolveConversationId(userId, request.conversationId()));
            question.setQuestionText(request.question());
            question.setTitle(buildConversationTitle(request.question()));
            question.setAnswerText(answer);
            question.setModelName(modelName);
            question.setPromptTokens(usage.promptTokens());
            question.setCompletionTokens(usage.completionTokens());
            question.setTotalTokens(usage.totalTokens());
            question.setCustomModel(customModel);
            question.setQuestionStatus(QuestionStatus.SUCCESS);
            RagQuestionEntity saved = ragQuestionRepository.save(question);
            saved = ensureConversationId(saved);
            saveSources(saved.getId(), chunks);
            return saved;
        } catch (Exception ignored) {
            RagQuestionEntity fallback = new RagQuestionEntity();
            fallback.setId(0L);
              fallback.setConversationId(0L);
              return fallback;
          }
      }

    private TokenUsage completionUsage(LlmCompletion completion, String prompt, String answer) {
        if (completion.totalTokens() != null || completion.promptTokens() != null || completion.completionTokens() != null) {
            return new TokenUsage(completion.promptTokens(), completion.completionTokens(), completion.totalTokens());
        }
        return estimateUsage(prompt, answer);
    }

    private TokenUsage estimateUsage(String prompt, String answer) {
        int promptTokens = estimateTokens(prompt);
        int completionTokens = estimateTokens(answer);
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    private int estimateTokens(String text) {
        String value = text == null ? "" : text;
        return Math.max(1, (int) Math.ceil(value.length() / 1.8));
    }

    private void recordUsageLog(long userId, String action, Long questionId, ChatRequest request, LlmCompletion completion) {
        if (questionId == null || questionId <= 0) {
            return;
        }
        String detail = "model=" + completion.modelName()
            + ", customModel=" + completion.customModel()
            + ", promptTokens=" + valueOrZero(completion.promptTokens())
            + ", completionTokens=" + valueOrZero(completion.completionTokens())
            + ", totalTokens=" + valueOrZero(completion.totalTokens())
            + ", mode=" + request.mode()
            + (request.materialId() == null ? "" : ", materialId=" + request.materialId());
        UsageRecordEntity record = new UsageRecordEntity();
        record.setUserId(userId);
        record.setAction(action);
        record.setTargetType("RAG_QUESTION");
        record.setTargetId(questionId);
        record.setModelName(completion.modelName());
        record.setPromptTokens(completion.promptTokens());
        record.setCompletionTokens(completion.completionTokens());
        record.setTotalTokens(completion.totalTokens());
        record.setDetail(detail);
        usageRecordRepository.save(record);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }

    private List<ScoredChunk> findChunksById(long userId, ChatRequest request) {
        if (request.materialId() == null) {
            return List.of();
        }
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(request.materialId(), userId)
            .orElse(null);
        if (material == null) {
            return List.of();
        }
        return materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId()).stream()
            .filter(chunk -> chunk.getId().equals(request.chunkId()))
            .map(chunk -> new ScoredChunk(material, chunk, 1.0))
            .toList();
    }

    private String buildQuestionWithHistory(String question, List<ChatMessage> history, boolean generalChat) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("对话历史：\n");
        int maxHistoryItems = generalChat ? 6 : 10;
        int start = Math.max(0, history.size() - maxHistoryItems);
        for (int i = start; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            sb.append(msg.role()).append("：").append(msg.content()).append("\n");
        }
        sb.append("\n当前问题：").append(question);
        return sb.toString();
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
            String grounded = groundFollowUpQuestion(current, topic);
            if (!grounded.equals(current)) {
                return grounded;
            }
            return topic + " " + current;
        }
        return current;
    }

    private String groundFollowUpQuestion(String question, String topic) {
        String grounded = question.trim()
            .replaceFirst("^(?:\\u90a3|\\u90a3\\u4e48|\\u6240\\u4ee5)?(?:\\u5b83|\\u8fd9\\u4e2a|\\u8be5|\\u8fd9|\\u4e0a\\u8ff0|\\u524d\\u9762)(\\u7684)?", Matcher.quoteReplacement(topic) + "$1")
            .replaceFirst("(?i)\\b(it|this|that|they|them|its|those|these)\\b", Matcher.quoteReplacement(topic));
        return grounded.trim();
    }

    private boolean isFollowUpQuestion(String question) {
        String normalized = normalizeForTermMatch(question);
        if (normalized.length() <= 18 && containsAny(normalized, "它", "这个", "该", "上述", "前面", "刚才", "优缺点", "作用", "区别", "怎么", "为什么")) {
            return true;
        }
        return Pattern.compile("(?i)\\b(it|this|that|they|them|its|those|these)\\b").matcher(question).find();
    }

    private String recentConversationTopic(List<ChatMessage> history) {
        String userTopic = recentConversationTopic(history, true);
        if (userTopic != null && !userTopic.isBlank()) {
            return userTopic;
        }
        return recentConversationTopic(history, false);
    }

    private String recentConversationTopic(List<ChatMessage> history, boolean userOnly) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }
            if (userOnly && !"user".equalsIgnoreCase(String.valueOf(message.role()))) {
                continue;
            }
            String topic = extractConversationTopic(message.content());
            if (topic != null && !topic.isBlank()) {
                return topic;
            }
        }
        return null;
    }

    private KeywordQuery extractOpenEndedTopicQuery(String question) {
        Matcher introduce = Pattern.compile("(?:\\u804a\\u804a|\\u4ecb\\u7ecd\\u4e00\\u4e0b|\\u8bb2\\u8bb2|\\u8bf4\\u8bf4|\\u89e3\\u91ca\\u4e00\\u4e0b)\\s*(.{2,80}?)(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (introduce.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, introduce.group(1));
        }
        Matcher principle = Pattern.compile("(.{2,80}?)\\s*(?:\\u662f)?(?:\\u600e\\u4e48\\u5de5\\u4f5c|\\u5982\\u4f55\\u5de5\\u4f5c|\\u5de5\\u4f5c\\u539f\\u7406|\\u539f\\u7406)(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (principle.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, principle.group(1));
        }
        Matcher reason = Pattern.compile("(?:\\u4e3a\\u4ec0\\u4e48|\\u4e3a\\u4f55)\\s*(?:\\u9700\\u8981|\\u8981|\\u4f7f\\u7528)?\\s*(.{2,80}?)(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (reason.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, reason.group(1));
        }
        Matcher englishIntro = Pattern.compile("(?i)(?:introduce|explain|describe|talk\\s+about)\\s+(.{2,80})(?:[?.!]|$)").matcher(question);
        if (englishIntro.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, englishIntro.group(1));
        }
        Matcher englishHow = Pattern.compile("(?i)(?:how|why)\\s+(?:does|do|is|are)?\\s*(.{2,80}?)(?:\\s+work|\\s+needed|\\s+used)?(?:[?.!]|$)").matcher(question);
        if (englishHow.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, englishHow.group(1));
        }
        return null;
    }

    private String extractConversationTopic(String text) {
        KeywordQuery keywordQuery = extractKeywordQuery(text);
        if (keywordQuery != null && !keywordQuery.terms().isEmpty()) {
            return String.join(" ", keywordQuery.terms());
        }
        String definitionTerm = extractDefinitionTerm(text);
        if (definitionTerm != null && !definitionTerm.isBlank()) {
            return definitionTerm;
        }
        Matcher cnTopic = Pattern.compile("([\\p{IsHan}A-Za-z0-9+#./-]{2,40}(?:协议|模型|算法|数据库|索引|事务|机制|框架|流程|原理|系统|方法|概念))").matcher(text);
        if (cnTopic.find()) {
            return cleanKeywordTerm(cnTopic.group(1));
        }
        Matcher technicalTerm = Pattern.compile("\\b([A-Za-z][A-Za-z0-9+#./-]{1,40})\\b").matcher(text);
        while (technicalTerm.find()) {
            String term = technicalTerm.group(1);
            if (!isWeakQueryTerm(term.toLowerCase(Locale.ROOT))) {
                return term;
            }
        }
        return null;
    }

    private boolean isGeneralChat(ChatRequest request) {
        return "GENERAL".equalsIgnoreCase(String.valueOf(request.mode()).trim());
    }

    private boolean isMaterialChat(ChatRequest request) {
        return "MATERIAL".equalsIgnoreCase(String.valueOf(request.mode()).trim());
    }

    private void validateCurrentMaterialForChat(long userId, Long materialId) {
        if (materialId == null) {
            throw new BusinessException(400, "materialId is required for current material chat");
        }
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElseThrow(() -> new BusinessException(404, "material not found"));
        if (material.getParseStatus() != MaterialParseStatus.SUCCESS) {
            throw new BusinessException(400, "material has not been parsed successfully");
        }
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
            .map(this::toSourceResponse)
            .toList();
        return toHistoryDetail(userId, latestQuestion, sources, conversationQuestions);
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
    public RagEvaluationResponse evaluateHistory(long userId, long questionId) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "Question not found"));
        List<RagQuestionSourceEntity> sources = ragQuestionSourceRepository.findByQuestionIdOrderByRankScoreDesc(questionId);

        String sourceContext = sources.stream()
            .map(RagQuestionSourceEntity::getExcerpt)
            .map(this::cleanExcerptText)
            .collect(Collectors.joining(" "));
        List<String> questionTerms = significantEvaluationTerms(question.getQuestionText(), 10);
        List<String> answerTerms = significantEvaluationTerms(question.getAnswerText(), 16);

        double contextRelevanceScore = scoreTermCoverage(sourceContext, questionTerms);
        if (!sources.isEmpty()) {
            double averageRankScore = sources.stream()
                .mapToDouble(source -> source.getRankScore() == null ? 0.0 : source.getRankScore())
                .average()
                .orElse(0.0);
            contextRelevanceScore = clamp((contextRelevanceScore * 0.85) + (Math.min(averageRankScore, 1.0) * 0.15));
        }

        double faithfulnessScore = scoreTermCoverage(sourceContext, answerTerms);
        if (sources.isEmpty()) {
            faithfulnessScore = 0.0;
            contextRelevanceScore = 0.0;
        }
        double overallScore = clamp((faithfulnessScore * 0.55) + (contextRelevanceScore * 0.45));
        String verdict = evaluationVerdict(overallScore, faithfulnessScore, contextRelevanceScore);
        String evidence = buildEvaluationEvidence(questionTerms, answerTerms, sourceContext, sources.size());

        RagEvaluationEntity evaluation = ragEvaluationRepository.findByQuestionIdAndUserId(questionId, userId)
            .orElseGet(RagEvaluationEntity::new);
        evaluation.setUserId(userId);
        evaluation.setQuestionId(questionId);
        evaluation.setFaithfulnessScore(roundScore(faithfulnessScore));
        evaluation.setContextRelevanceScore(roundScore(contextRelevanceScore));
        evaluation.setOverallScore(roundScore(overallScore));
        evaluation.setVerdict(verdict);
        evaluation.setEvidence(evidence);
        return toEvaluationResponse(ragEvaluationRepository.save(evaluation));
    }

    @Transactional
    public RagEvaluationResponse latestEvaluation(long userId, long questionId) {
        ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "Question not found"));
        return ragEvaluationRepository.findByQuestionIdAndUserId(questionId, userId)
            .map(this::toEvaluationResponse)
            .orElseGet(() -> evaluateHistory(userId, questionId));
    }

    @Transactional
    public RagEvaluationSuiteResponse runEvaluationSuite(long userId, RagEvaluationSuiteRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new BusinessException(400, "evaluation cases are required");
        }
        return runEvaluationCases(userId, request.cases());
    }

    @Transactional(readOnly = true)
    public List<RagEvaluationSuiteSummaryResponse> evaluationSuites(long userId) {
        return ragEvaluationSuiteRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
            .map(suite -> toSuiteSummaryResponse(
                suite,
                ragEvaluationSuiteCaseRepository.findBySuiteIdOrderByCaseIndexAsc(suite.getId()).size()
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public RagEvaluationSuiteDetailResponse evaluationSuiteDetail(long userId, long suiteId) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        return toSuiteDetailResponse(suite);
    }

    @Transactional
    public RagEvaluationSuiteDetailResponse saveEvaluationSuite(long userId, RagEvaluationSuiteSaveRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new BusinessException(400, "evaluation cases are required");
        }
        RagEvaluationSuiteEntity suite = new RagEvaluationSuiteEntity();
        suite.setUserId(userId);
        suite.setName(normalizeSuiteName(request.name()));
        suite.setDescription(normalizeOptionalText(request.description()));
        RagEvaluationSuiteEntity savedSuite = ragEvaluationSuiteRepository.save(suite);
        saveSuiteCases(savedSuite.getId(), request.cases());
        return toSuiteDetailResponse(savedSuite);
    }

    @Transactional
    public RagEvaluationSuiteDetailResponse updateEvaluationSuite(long userId, long suiteId, RagEvaluationSuiteSaveRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new BusinessException(400, "evaluation cases are required");
        }
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        suite.setName(normalizeSuiteName(request.name()));
        suite.setDescription(normalizeOptionalText(request.description()));
        RagEvaluationSuiteEntity savedSuite = ragEvaluationSuiteRepository.save(suite);
        ragEvaluationSuiteCaseRepository.deleteBySuiteId(savedSuite.getId());
        saveSuiteCases(savedSuite.getId(), request.cases());
        return toSuiteDetailResponse(savedSuite);
    }

    @Transactional
    public void deleteEvaluationSuite(long userId, long suiteId) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        ragEvaluationSuiteRunRepository.deleteBySuiteId(suite.getId());
        ragEvaluationSuiteCaseRepository.deleteBySuiteId(suite.getId());
        ragEvaluationSuiteRepository.delete(suite);
    }

    @Transactional
    public RagEvaluationSuiteRunResponse runSavedEvaluationSuite(long userId, long suiteId) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        return runEvaluationSuiteEntity(suite, LocalDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<RagEvaluationSuiteRunResponse> evaluationSuiteRuns(long userId, long suiteId) {
        ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        return ragEvaluationSuiteRunRepository.findBySuiteIdAndUserIdOrderByCreatedAtDesc(suiteId, userId).stream()
            .map(this::toSuiteRunResponse)
            .toList();
    }

    @Transactional
    public RagEvaluationSuiteDetailResponse updateEvaluationSuiteSchedule(long userId, long suiteId, RagEvaluationSuiteScheduleRequest request) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        applySchedule(suite, request.scheduled(), request.intervalHours(), LocalDateTime.now());
        return toSuiteDetailResponse(ragEvaluationSuiteRepository.save(suite));
    }

    @Transactional(readOnly = true)
    public List<RagEvaluationSuiteEntity> dueScheduledEvaluationSuites(LocalDateTime now) {
        return ragEvaluationSuiteRepository.findByScheduledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now);
    }

    @Transactional
    public RagEvaluationSuiteRunResponse runScheduledEvaluationSuite(long suiteId, LocalDateTime now) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findById(suiteId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        if (!Boolean.TRUE.equals(suite.getScheduled())) {
            throw new BusinessException(400, "evaluation suite is not scheduled");
        }
        return runEvaluationSuiteEntity(suite, now == null ? LocalDateTime.now() : now);
    }

    private RagEvaluationSuiteRunResponse runEvaluationSuiteEntity(RagEvaluationSuiteEntity suite, LocalDateTime runAt) {
        List<RagEvaluationCaseRequest> cases = suiteCases(suite.getId());
        if (cases.isEmpty()) {
            throw new BusinessException(400, "evaluation cases are required");
        }
        RagEvaluationSuiteResponse result = runEvaluationCases(suite.getUserId(), cases);
        RagEvaluationSuiteRunEntity run = new RagEvaluationSuiteRunEntity();
        run.setSuiteId(suite.getId());
        run.setUserId(suite.getUserId());
        run.setTotalCases(result.totalCases());
        run.setPassedCases(result.passedCases());
        run.setPassRate(result.passRate());
        run.setAverageFaithfulnessScore(result.averageFaithfulnessScore());
        run.setAverageContextRelevanceScore(result.averageContextRelevanceScore());
        run.setAverageOverallScore(result.averageOverallScore());
        run.setResultJson(writeJson(result));
        RagEvaluationSuiteRunEntity savedRun = ragEvaluationSuiteRunRepository.save(run);

        suite.setLastTotalCases(result.totalCases());
        suite.setLastPassedCases(result.passedCases());
        suite.setLastPassRate(result.passRate());
        suite.setLastAverageOverallScore(result.averageOverallScore());
        LocalDateTime effectiveRunAt = savedRun.getCreatedAt() == null ? runAt : savedRun.getCreatedAt();
        suite.setLastRunAt(effectiveRunAt);
        if (Boolean.TRUE.equals(suite.getScheduled())) {
            suite.setNextRunAt(nextScheduleTime(effectiveRunAt, suite.getScheduleIntervalHours()));
        }
        ragEvaluationSuiteRepository.save(suite);
        return toSuiteRunResponse(savedRun);
    }

    private RagEvaluationSuiteResponse runEvaluationCases(long userId, List<RagEvaluationCaseRequest> cases) {
        List<RagEvaluationCaseResponse> caseResponses = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            RagEvaluationCaseRequest testCase = cases.get(i);
            RagChatResponse chatResponse = chat(userId, new ChatRequest(
                testCase.question(),
                testCase.materialId(),
                testCase.materialId() == null ? "GENERAL" : "MATERIAL",
                null,
                null,
                null,
                null,
                null,
                "STUDY",
                null,
                null
            ));
            RagEvaluationResponse evaluation = evaluateHistory(userId, chatResponse.questionId());
            String answerText = chatResponse.answer() == null ? "" : chatResponse.answer();
            String sourceText = chatResponse.sources().stream()
                .map(RagSourceResponse::excerpt)
                .collect(Collectors.joining(" "));
            TermCoverage answerCoverage = expectedTermCoverage(answerText, testCase.expectedAnswerTerms());
            TermCoverage sourceCoverage = expectedTermCoverage(sourceText, testCase.expectedSourceTerms());
            boolean hasExpectedTerms = hasExpectedTerms(testCase.expectedAnswerTerms()) || hasExpectedTerms(testCase.expectedSourceTerms());
            boolean passed = hasExpectedTerms
                ? answerCoverage.score() >= 0.70 && sourceCoverage.score() >= 0.70
                : "PASS".equals(evaluation.verdict());
            caseResponses.add(new RagEvaluationCaseResponse(
                i + 1,
                chatResponse.questionId(),
                testCase.question(),
                evaluation.faithfulnessScore(),
                evaluation.contextRelevanceScore(),
                evaluation.overallScore(),
                roundScore(answerCoverage.score()),
                roundScore(sourceCoverage.score()),
                evaluation.verdict(),
                passed,
                answerCoverage.missingTerms(),
                sourceCoverage.missingTerms()
            ));
        }

        int passedCases = (int) caseResponses.stream().filter(RagEvaluationCaseResponse::passed).count();
        double totalCases = caseResponses.size();
        return new RagEvaluationSuiteResponse(
            caseResponses.size(),
            passedCases,
            roundScore(totalCases == 0 ? 0.0 : passedCases / totalCases),
            roundScore(caseResponses.stream().mapToDouble(RagEvaluationCaseResponse::faithfulnessScore).average().orElse(0.0)),
            roundScore(caseResponses.stream().mapToDouble(RagEvaluationCaseResponse::contextRelevanceScore).average().orElse(0.0)),
            roundScore(caseResponses.stream().mapToDouble(RagEvaluationCaseResponse::overallScore).average().orElse(0.0)),
            caseResponses
        );
    }

    private void saveSuiteCases(Long suiteId, List<RagEvaluationCaseRequest> cases) {
        for (int i = 0; i < cases.size(); i++) {
            RagEvaluationCaseRequest request = cases.get(i);
            RagEvaluationSuiteCaseEntity entity = new RagEvaluationSuiteCaseEntity();
            entity.setSuiteId(suiteId);
            entity.setCaseIndex(i + 1);
            entity.setQuestion(request.question() == null ? "" : request.question().trim());
            entity.setMaterialId(request.materialId());
            entity.setExpectedAnswerTerms(writeJson(safeTerms(request.expectedAnswerTerms())));
            entity.setExpectedSourceTerms(writeJson(safeTerms(request.expectedSourceTerms())));
            ragEvaluationSuiteCaseRepository.save(entity);
        }
    }

    private void applySchedule(RagEvaluationSuiteEntity suite, boolean scheduled, Integer intervalHours, LocalDateTime now) {
        int normalizedIntervalHours = normalizeScheduleIntervalHours(intervalHours);
        suite.setScheduled(scheduled);
        suite.setScheduleIntervalHours(normalizedIntervalHours);
        suite.setNextRunAt(scheduled ? nextScheduleTime(now, normalizedIntervalHours) : null);
    }

    private int normalizeScheduleIntervalHours(Integer intervalHours) {
        if (intervalHours == null) {
            return 24;
        }
        return Math.max(1, Math.min(24 * 30, intervalHours));
    }

    private LocalDateTime nextScheduleTime(LocalDateTime from, Integer intervalHours) {
        return (from == null ? LocalDateTime.now() : from)
            .plus(Duration.ofHours(normalizeScheduleIntervalHours(intervalHours)));
    }

    private List<RagEvaluationCaseRequest> suiteCases(Long suiteId) {
        return ragEvaluationSuiteCaseRepository.findBySuiteIdOrderByCaseIndexAsc(suiteId).stream()
            .map(entity -> new RagEvaluationCaseRequest(
                entity.getQuestion(),
                entity.getMaterialId(),
                readStringList(entity.getExpectedAnswerTerms()),
                readStringList(entity.getExpectedSourceTerms())
            ))
            .toList();
    }

    @Transactional
    public void deleteHistory(long userId, long questionId) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "Question not found"));
        for (RagQuestionEntity conversationQuestion : questionsInConversation(userId, question)) {
            Long id = conversationQuestion.getId();
            userFavoriteRepository.deleteByUserIdAndQuestionId(userId, id);
            ragQuestionSourceRepository.deleteByQuestionId(id);
            ragFeedbackRepository.deleteByQuestionId(id);
            ragEvaluationRepository.deleteByQuestionId(id);
            ragQuestionRepository.delete(conversationQuestion);
        }
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
    }

    @Transactional
    public RagSummaryResponse summarize(long userId, SummarizeRequest request) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(request.materialId(), userId)
            .orElseThrow(() -> new BusinessException(404, "material not found"));
        if (material.getParseStatus() != MaterialParseStatus.SUCCESS) {
            throw new BusinessException(400, "material has not been parsed successfully");
        }

        List<MaterialChunkEntity> chunks = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId());
        if (chunks.isEmpty()) {
            throw new BusinessException(400, "material has no chunks to summarize");
        }

        LlmCompletion completion = thirdPartyLlmClient
            .summarize(material.getTitle(), chunks.stream().limit(8).map(chunk -> excerpt(chunk.getChunkText())).toList())
            .orElseGet(() -> new LlmCompletion(buildCleanSummary(material, chunks), "local-rag-demo"));

        MaterialSummaryEntity entity = new MaterialSummaryEntity();
        entity.setMaterialId(material.getId());
        entity.setUserId(userId);
        entity.setSummaryText(completion.content());
        entity.setSummaryType("AUTO");
        entity.setModelName(completion.modelName());
        MaterialSummaryEntity saved = materialSummaryRepository.save(entity);

        material.setSummaryStatus(MaterialSummaryStatus.SUCCESS);
        learningMaterialRepository.save(material);

        return new RagSummaryResponse(
            saved.getId(),
            material.getId(),
            material.getTitle(),
            saved.getSummaryText(),
            saved.getSummaryType(),
            saved.getModelName(),
            chunks.size(),
            saved.getCreatedAt() == null ? null : saved.getCreatedAt().format(DATETIME_FORMATTER)
        );
    }

    @Transactional(readOnly = true)
    public RagSummaryResponse latestSummary(long userId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElseThrow(() -> new BusinessException(404, "material not found"));
        MaterialSummaryEntity summary = materialSummaryRepository.findFirstByMaterialIdAndUserIdOrderByCreatedAtDesc(materialId, userId)
            .orElseThrow(() -> new BusinessException(404, "summary not found"));
        int sourceCount = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId).size();
        return new RagSummaryResponse(
            summary.getId(),
            material.getId(),
            material.getTitle(),
            summary.getSummaryText(),
            summary.getSummaryType(),
            summary.getModelName(),
            sourceCount,
            summary.getCreatedAt() == null ? null : summary.getCreatedAt().format(DATETIME_FORMATTER)
        );
    }

    @Transactional(readOnly = true)
    public List<RagSummaryResponse> summaryHistory(long userId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElseThrow(() -> new BusinessException(404, "material not found"));
        int sourceCount = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId).size();
        return materialSummaryRepository.findByMaterialIdAndUserIdOrderByCreatedAtDesc(materialId, userId).stream()
            .map(summary -> new RagSummaryResponse(
                summary.getId(),
                material.getId(),
                material.getTitle(),
                summary.getSummaryText(),
                summary.getSummaryType(),
                summary.getModelName(),
                sourceCount,
                summary.getCreatedAt() == null ? null : summary.getCreatedAt().format(DATETIME_FORMATTER)
            ))
            .toList();
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
        return new FavoriteItemResponse(
            saved.getId(),
            question.getId(),
            effectiveConversationId(question),
            question.getQuestionText(),
            question.getAnswerText(),
            saved.getCreatedAt() == null ? null : saved.getCreatedAt().format(DATETIME_FORMATTER),
            toConversationMessages(questionsInConversation(userId, question))
        );
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
            .map(favorite -> {
                RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(favorite.getQuestionId(), userId)
                    .orElse(null);
                if (question == null) {
                    return null;
                }
                return new FavoriteItemResponse(
                    favorite.getId(),
                    question.getId(),
                    effectiveConversationId(question),
                    question.getQuestionText(),
                    question.getAnswerText(),
                    favorite.getCreatedAt() == null ? null : favorite.getCreatedAt().format(DATETIME_FORMATTER),
                    toConversationMessages(questionsInConversation(userId, question))
                );
            })
            .filter(item -> item != null)
            .toList();
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
            question.getCreatedAt() == null ? null : question.getCreatedAt().format(DATETIME_FORMATTER),
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
            question.getCreatedAt() == null ? null : question.getCreatedAt().format(DATETIME_FORMATTER),
            toConversationMessages(conversationQuestions),
            sources,
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
            feedback.getUpdatedAt() == null ? null : feedback.getUpdatedAt().format(DATETIME_FORMATTER)
        );
    }

    private RagEvaluationResponse toEvaluationResponse(RagEvaluationEntity evaluation) {
        return new RagEvaluationResponse(
            evaluation.getId(),
            evaluation.getQuestionId(),
            evaluation.getFaithfulnessScore(),
            evaluation.getContextRelevanceScore(),
            evaluation.getOverallScore(),
            evaluation.getVerdict(),
            evaluation.getEvidence(),
            evaluation.getUpdatedAt() == null ? null : evaluation.getUpdatedAt().format(DATETIME_FORMATTER)
        );
    }

    private RagEvaluationSuiteSummaryResponse toSuiteSummaryResponse(RagEvaluationSuiteEntity suite, int caseCount) {
        return new RagEvaluationSuiteSummaryResponse(
            suite.getId(),
            suite.getName(),
            suite.getDescription(),
            caseCount,
            suite.getLastTotalCases(),
            suite.getLastPassedCases(),
            suite.getLastPassRate(),
            suite.getLastAverageOverallScore(),
            formatDateTime(suite.getLastRunAt()),
            Boolean.TRUE.equals(suite.getScheduled()),
            normalizeScheduleIntervalHours(suite.getScheduleIntervalHours()),
            formatDateTime(suite.getNextRunAt()),
            formatDateTime(suite.getUpdatedAt())
        );
    }

    private RagEvaluationSuiteDetailResponse toSuiteDetailResponse(RagEvaluationSuiteEntity suite) {
        return new RagEvaluationSuiteDetailResponse(
            suite.getId(),
            suite.getName(),
            suite.getDescription(),
            suiteCases(suite.getId()),
            ragEvaluationSuiteRunRepository.findFirstBySuiteIdAndUserIdOrderByCreatedAtDesc(suite.getId(), suite.getUserId())
                .map(this::toSuiteRunResponse)
                .orElse(null),
            Boolean.TRUE.equals(suite.getScheduled()),
            normalizeScheduleIntervalHours(suite.getScheduleIntervalHours()),
            formatDateTime(suite.getNextRunAt()),
            formatDateTime(suite.getCreatedAt()),
            formatDateTime(suite.getUpdatedAt())
        );
    }

    private RagEvaluationSuiteRunResponse toSuiteRunResponse(RagEvaluationSuiteRunEntity run) {
        return new RagEvaluationSuiteRunResponse(
            run.getId(),
            run.getSuiteId(),
            run.getTotalCases(),
            run.getPassedCases(),
            run.getPassRate(),
            run.getAverageFaithfulnessScore(),
            run.getAverageContextRelevanceScore(),
            run.getAverageOverallScore(),
            readSuiteResult(run.getResultJson()),
            formatDateTime(run.getCreatedAt())
        );
    }

    private String normalizeSuiteName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isBlank()) {
            throw new BusinessException(400, "evaluation suite name is required");
        }
        return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private List<String> safeTerms(List<String> terms) {
        if (terms == null) {
            return List.of();
        }
        return terms.stream()
            .map(term -> term == null ? "" : term.trim())
            .filter(term -> !term.isBlank())
            .distinct()
            .toList();
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<String> values = new ArrayList<>();
            for (JsonNode node : root) {
                if (node.isTextual() && !node.asText().isBlank()) {
                    values.add(node.asText());
                }
            }
            return values;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private RagEvaluationSuiteResponse readSuiteResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, RagEvaluationSuiteResponse.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? null : value.format(DATETIME_FORMATTER);
    }

    private List<String> significantEvaluationTerms(String text, int limit) {
        List<String> terms = significantQueryTerms(text);
        if (!terms.isEmpty()) {
            return terms.stream().limit(limit).toList();
        }
        List<String> fallbackTerms = new ArrayList<>();
        Matcher matcher = Pattern.compile("[\\p{IsHan}a-zA-Z0-9+#./-]{2,}").matcher(text == null ? "" : text);
        while (matcher.find()) {
            String term = cleanKeywordTerm(matcher.group());
            if (term == null) {
                continue;
            }
            String normalized = normalizeForTermMatch(term);
            if (normalized.length() >= 2 && !isWeakQueryTerm(normalized)) {
                fallbackTerms.add(normalized);
            }
        }
        return fallbackTerms.stream().distinct().limit(limit).toList();
    }

    private double scoreTermCoverage(String text, List<String> terms) {
        if (terms.isEmpty()) {
            return 1.0;
        }
        String normalizedText = normalizeForTermMatch(text);
        long matched = terms.stream().filter(normalizedText::contains).count();
        return (double) matched / terms.size();
    }

    private String evaluationVerdict(double overallScore, double faithfulnessScore, double contextRelevanceScore) {
        if (overallScore >= 0.72 && faithfulnessScore >= 0.65 && contextRelevanceScore >= 0.55) {
            return "PASS";
        }
        if (overallScore >= 0.42 && faithfulnessScore >= 0.35 && contextRelevanceScore >= 0.30) {
            return "WARN";
        }
        return "FAIL";
    }

    private String buildEvaluationEvidence(
        List<String> questionTerms,
        List<String> answerTerms,
        String sourceContext,
        int sourceCount
    ) {
        String normalizedContext = normalizeForTermMatch(sourceContext);
        List<String> supportedAnswerTerms = answerTerms.stream()
            .filter(normalizedContext::contains)
            .limit(8)
            .toList();
        List<String> missingAnswerTerms = answerTerms.stream()
            .filter(term -> !normalizedContext.contains(term))
            .limit(8)
            .toList();
        List<String> matchedQuestionTerms = questionTerms.stream()
            .filter(normalizedContext::contains)
            .limit(8)
            .toList();
        return "sources=" + sourceCount
            + "; matchedQuestionTerms=" + String.join(",", matchedQuestionTerms)
            + "; supportedAnswerTerms=" + String.join(",", supportedAnswerTerms)
            + "; missingAnswerTerms=" + String.join(",", missingAnswerTerms);
    }

    private TermCoverage expectedTermCoverage(String text, List<String> expectedTerms) {
        List<String> terms = expectedTerms == null ? List.of() : expectedTerms.stream()
            .map(term -> term == null ? "" : normalizeForTermMatch(term))
            .filter(term -> term.length() >= 2)
            .distinct()
            .toList();
        if (terms.isEmpty()) {
            return new TermCoverage(1.0, List.of());
        }
        String normalizedText = normalizeForTermMatch(text);
        List<String> missingTerms = terms.stream()
            .filter(term -> !normalizedText.contains(term))
            .toList();
        return new TermCoverage((double) (terms.size() - missingTerms.size()) / terms.size(), missingTerms);
    }

    private boolean hasExpectedTerms(List<String> expectedTerms) {
        return expectedTerms != null && expectedTerms.stream().anyMatch(term -> term != null && !normalizeForTermMatch(term).isBlank());
    }

    private double roundScore(double score) {
        return Math.round(clamp(score) * 1000.0) / 1000.0;
    }

    private double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
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

    private List<RagQuestionEntity> latestQuestionsByConversation(long userId) {
        Map<Long, RagQuestionEntity> latestByConversation = new LinkedHashMap<>();
        for (RagQuestionEntity question : ragQuestionRepository.findByUserIdOrderByPinnedDescCreatedAtDesc(userId)) {
            Long conversationId = effectiveConversationId(question);
            latestByConversation.putIfAbsent(conversationId, question);
        }
        return latestByConversation.values().stream().toList();
    }

    private List<RagHistoryMessageResponse> toConversationMessages(List<RagQuestionEntity> questions) {
        List<RagHistoryMessageResponse> messages = new ArrayList<>();
        for (RagQuestionEntity question : questions) {
            messages.add(new RagHistoryMessageResponse(question.getId(), "user", question.getQuestionText()));
            messages.add(new RagHistoryMessageResponse(question.getId(), "assistant", question.getAnswerText()));
        }
        return messages;
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

    private Long resolveConversationId(long userId, Long requestedConversationId) {
        if (requestedConversationId == null || requestedConversationId <= 0) {
            return null;
        }
        return ragQuestionRepository.findByIdAndUserId(requestedConversationId, userId)
            .map(this::effectiveConversationId)
            .orElse(null);
    }

    private RagQuestionEntity ensureConversationId(RagQuestionEntity question) {
        if (question.getConversationId() == null) {
            question.setConversationId(question.getId());
            return ragQuestionRepository.save(question);
        }
        return question;
    }

    private Long effectiveConversationId(RagQuestionEntity question) {
        return question.getConversationId() == null ? question.getId() : question.getConversationId();
    }

    private String buildConversationTitle(String questionText) {
        String normalized = normalizeHistoryTitle(questionText, questionText);
        if (normalized.length() <= 28) {
            return normalized;
        }
        return normalized.substring(0, 28) + "...";
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

    private List<RagQuestionSourceEntity> saveSources(long questionId, List<ScoredChunk> topChunks) {
        List<RagQuestionSourceEntity> saved = new ArrayList<>();
        for (ScoredChunk chunk : topChunks) {
            RagQuestionSourceEntity entity = new RagQuestionSourceEntity();
            entity.setQuestionId(questionId);
            entity.setMaterialId(chunk.material().getId());
            entity.setChunkId(chunk.chunk().getId());
            entity.setSourceTitle(chunk.material().getTitle());
            entity.setPageNo(chunk.chunk().getPageNo());
            entity.setExcerpt(excerpt(chunk));
            entity.setRankScore(chunk.score());
            entity.setCreatedAt(LocalDateTime.now());
            saved.add(ragQuestionSourceRepository.save(entity));
        }
        return saved;
    }

    private RagSourceResponse toSourceResponse(RagQuestionSourceEntity source) {
        return new RagSourceResponse(
            source.getMaterialId(),
            source.getChunkId(),
            source.getSourceTitle(),
            source.getPageNo(),
            source.getExcerpt(),
            source.getRankScore()
        );
    }

    private RagSourceResponse toSourceResponse(ScoredChunk chunk) {
        MaterialChunkEntity materialChunk = chunk.chunk();
        LearningMaterialEntity material = chunk.material();
        return new RagSourceResponse(
            material.getId(),
            materialChunk.getId(),
            material.getTitle(),
            materialChunk.getPageNo(),
            excerpt(chunk),
            chunk.score()
        );
    }

    private String buildCleanAnswer(String question, List<ScoredChunk> topChunks, String answerStyle) {
        if (topChunks.isEmpty()) {
            return "当前资料未覆盖这个问题。你可以补充对应章节、上传相关资料，或者把问题改得更贴近课程标题、章节名和关键词。";
        }
        if (isHomeworkStyle(answerStyle)) {
            return buildHomeworkFallbackAnswer(question, topChunks);
        }
        String evidence = topChunks.stream()
            .map(chunk -> {
                MaterialChunkEntity materialChunk = chunk.chunk();
                LearningMaterialEntity material = chunk.material();
                String page = materialChunk.getPageNo() == null ? "未知页" : "第 " + materialChunk.getPageNo() + " 页";
                return "• 《" + material.getTitle() + "》" + page + "：" + excerpt(materialChunk.getChunkText());
            })
            .collect(Collectors.joining("\n"));
        return "结论：问题\"" + question + "\"可以结合以下资料片段理解。\n\n依据：\n"
            + evidence
            + "\n\n建议：如果你要用于作业、答辩或复习，可以继续补充对应章节以获得更完整的引用。";
    }

    private String buildHomeworkFallbackAnswer(String question, List<ScoredChunk> topChunks) {
        String mainEvidence = topChunks.stream()
            .limit(3)
            .map(chunk -> excerpt(chunk.chunk().getChunkText()))
            .filter(text -> !text.isBlank())
            .collect(Collectors.joining("；"));
        String references = topChunks.stream()
            .map(chunk -> {
                MaterialChunkEntity materialChunk = chunk.chunk();
                LearningMaterialEntity material = chunk.material();
                String page = materialChunk.getPageNo() == null ? "未知页" : "第 " + materialChunk.getPageNo() + " 页";
                return "原文：《" + material.getTitle() + "》" + page + "：" + excerpt(materialChunk.getChunkText());
            })
            .distinct()
            .collect(Collectors.joining("\n"));
        return "作业答案：围绕\"" + question + "\"，可以这样回答：" + mainEvidence
            + "。这些内容说明该问题需要结合资料中的关键概念、过程和依据进行阐述，作答时应先概括结论，再用原文支撑观点。\n\n"
            + "原文依据：\n" + references + "\n\n"
            + "使用提示：提交作业前，可以把上面的答案改写成自己的表达，并根据题目要求补充定义、步骤或案例。";
    }

    private String sourceContext(ScoredChunk scoredChunk) {
        return sourceContext(scoredChunk, "[资料来源]");
    }

    private String sourceContext(ScoredChunk scoredChunk, String label) {
        MaterialChunkEntity materialChunk = scoredChunk.chunk();
        LearningMaterialEntity material = scoredChunk.material();
        String page = materialChunk.getPageNo() == null ? "未知页" : "第 " + materialChunk.getPageNo() + " 页";
        String section = materialChunk.getSectionTitle() == null || materialChunk.getSectionTitle().isBlank()
            ? "未命名章节"
            : materialChunk.getSectionTitle();
        String text = excerptWithImageMarkers(materialChunk.getChunkText());
        return label + "《" + material.getTitle() + "》/" + page + "/" + section
            + "\n[片段内容]\n原文：" + text;
    }

    private String excerptWithImageMarkers(String text) {
        if (text == null) {
            return "";
        }
        String cleaned = IMAGE_MARKER_PATTERN.matcher(text).replaceAll("");
        cleaned = IMAGE_OCR_PATTERN.matcher(cleaned).replaceAll("图片OCR：");
        String normalized = cleaned.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 500) {
            return normalized;
        }
        return normalized.substring(0, 500) + "...";
    }

    private String decorateAnswer(ChatRequest request, String content, List<ScoredChunk> selectedChunks) {
        String question = request.question();
        String answerStyle = request.answerStyle();
        if (request.selectedText() != null && !request.selectedText().isBlank()) {
            String selectedAnswer = cleanAnswerText(content);
            return selectedAnswer.isBlank() ? "未生成有效回答。" : selectedAnswer;
        }
        if (selectedChunks.isEmpty() && !isCasualQuestion(question)) {
            return noEvidenceAnswer(question);
        }
        String answer = cleanAnswerText(content);
        if (answer.isBlank()) {
            answer = "未生成有效回答。";
        }
        if (selectedChunks.isEmpty()) {
            if (isCasualQuestion(question)) {
                String casualAnswer = removeNoSourceNotice(answer);
                return casualAnswer.isBlank() ? casualFallbackAnswer(question) : casualAnswer;
            }
            String prefix = "当前资料未检索到足够页码依据，本次回答按通用问答给出。";
            if (answer.contains("当前资料未检索到足够页码") || answer.contains("资料中未检索到相关页码")) {
                return answer;
            }
            return prefix + "\n\n" + answer;
        }

        String evidence = selectedChunks.stream()
            .map(chunk -> {
                MaterialChunkEntity materialChunk = chunk.chunk();
                LearningMaterialEntity material = chunk.material();
                String page = materialChunk.getPageNo() == null ? "未知页" : "第 " + materialChunk.getPageNo() + " 页";
                return "《" + material.getTitle() + "》" + page;
            })
            .distinct()
            .collect(Collectors.joining("\n"));
        if (answer.contains("书本依据") || answer.contains("原文依据")) {
            return answer;
        }
        String title = isHomeworkStyle(answerStyle) ? "原文依据" : "资料依据";
        return answer + "\n\n" + title + "：\n" + evidence;
    }

    private String noEvidenceAnswer(String question) {
        return "当前资料未检索到足够页码依据，当前资料中未找到可以支撑该问题的依据。\n\n"
            + "问题：" + (question == null ? "" : question.trim()) + "\n\n"
            + "建议：请切换到更相关的页面或章节，选中原文后提问，或者换成资料中出现的关键词继续检索。";
    }

    private boolean isHomeworkStyle(String answerStyle) {
        return "HOMEWORK".equalsIgnoreCase(String.valueOf(answerStyle).trim());
    }

    private String decorateGeneralAnswer(String content) {
        String answer = cleanAnswerText(content);
        if (answer.isBlank()) {
            return "未生成有效回答。";
        }
        String cleaned = removeNoSourceNotice(answer);
        cleaned = cleaned
            .replaceAll("当前资料未覆盖[^。！？\\n]*[。！？\\n]*", "")
            .replaceAll("资料中未检索到[^。！？\\n]*[。！？\\n]*", "")
            .replaceAll("资料库中未命中[^。！？\\n]*[。！？\\n]*", "")
            .trim();
        if (cleaned.isBlank()) {
            return "抱歉，我暂时无法回答这个问题。请尝试换个方式提问。";
        }
        return cleaned;
    }

    private String buildGeneralFallbackAnswer(String question) {
        if (isCasualQuestion(question)) {
            return casualFallbackAnswer(question);
        }
        return "抱歉，当前 AI 模型未配置，无法回答这个问题。\n"
            + "请在设置中配置 LLM API 密钥后重试，或切换到《资料问答》模式，基于已上传的课程资料获取回答。";
    }

    private String cleanAnswerText(String content) {
        if (content == null) {
            return "";
        }
        return content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replaceAll("[ \\t]+\\n", "\n")
            .replaceAll("\\n{4,}", "\n\n\n")
            .trim();
    }

    private String removeNoSourceNotice(String answer) {
        return answer
            .replaceFirst("^资料中未检索到相关页码[。；，,：:、\\s]*", "")
            .replaceFirst("^资料中未找到对应来源页码[。；，,：:、\\s]*", "")
            .replaceFirst("^资料库中未命中足够依据[。；，,：:、\\s]*", "")
            .trim();
    }

    private boolean isCasualQuestion(String question) {
        String normalized = question == null ? "" : question.trim().replaceAll("[\\s。！？!?，,.、]+", "");
        if (normalized.isBlank()) {
            return true;
        }
        return Set.of(
            "你好", "您好", "嗨", "hi", "hello", "哈喽", "在吗", "谢谢", "好的", "ok", "嗯"
        ).contains(normalized.toLowerCase(Locale.ROOT));
    }

    private String casualFallbackAnswer(String question) {
        String normalized = question == null ? "" : question.trim().replaceAll("[\\s。！？!?，,.、]+", "");
        if ("谢谢".equals(normalized)) {
            return "不客气。";
        }
        if ("在吗".equals(normalized)) {
            return "在的，有什么可以帮你？";
        }
        return "你好！有什么可以帮你？";
    }

    private String buildCleanSummary(LearningMaterialEntity material, List<MaterialChunkEntity> chunks) {
        String highlights = chunks.stream()
            .limit(3)
            .map(chunk -> excerpt(chunk.getChunkText()))
            .collect(Collectors.joining("\n"));
        return "《" + material.getTitle() + "》知识总结：\n"
            + "1. 资料已解析为 " + chunks.size() + " 个可检索片段，可用于智能问答和来源引用。\n"
            + "2. 核心内容摘录：\n" + highlights + "\n"
            + "3. 建议学习方式：先通读上面的核心片段，再围绕概念定义、实现流程、常见问题继续追问。";
    }

    private String buildAnswer(String question, List<ScoredChunk> topChunks) {
        if (topChunks.isEmpty()) {
            return "当前资料库中暂时没有足够相关的片段可以直接回答这个问题。";
        }
        String joinedExcerpts = topChunks.stream()
            .map(chunk -> excerpt(chunk.chunk().getChunkText()))
            .collect(Collectors.joining("；"));
        return "根据资料检索结果，问题\"" + question + "\"可以结合以下片段作答：" + joinedExcerpts;
    }

    private String buildSummary(LearningMaterialEntity material, List<MaterialChunkEntity> chunks) {
        String highlights = chunks.stream()
            .limit(3)
            .map(chunk -> excerpt(chunk.getChunkText()))
            .collect(Collectors.joining(" "));
        return "Summary of " + material.getTitle() + ": the material contains " + chunks.size()
            + " searchable chunk(s). Key points: " + highlights;
    }

    private List<ScoredChunk> findScoredChunks(long userId, String question, Long materialId) {
        List<LearningMaterialEntity> materials = materialId == null
            ? learningMaterialRepository.findByOwnerIdOrderByCreatedAtDesc(userId)
            : learningMaterialRepository.findByIdAndOwnerId(materialId, userId).stream().toList();

        List<Bm25Scorer.ChunkData> allChunkData = new ArrayList<>();
        List<MaterialChunks> validMaterialChunks = new ArrayList<>();
        for (LearningMaterialEntity material : materials) {
            if (material.getParseStatus() != MaterialParseStatus.SUCCESS) {
                continue;
            }
            List<MaterialChunkEntity> chunks = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId());
            validMaterialChunks.add(new MaterialChunks(material, chunks));
            for (MaterialChunkEntity chunk : chunks) {
                allChunkData.add(new Bm25Scorer.ChunkData(chunk.getId(), retrievalText(chunk)));
            }
        }

        Bm25Scorer scorer = bm25Scorer(userId, materialId, validMaterialChunks, allChunkData);
        Set<String> questionTokens = scorer.tokenize(question);

        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (MaterialChunks materialChunks : validMaterialChunks) {
            for (MaterialChunkEntity chunk : materialChunks.chunks()) {
                double score = scorer.score(questionTokens, chunk.getId());
                scoredChunks.add(new ScoredChunk(materialChunks.material(), chunk, score));
            }
        }
        scoredChunks.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scoredChunks;
    }

    private Bm25Scorer bm25Scorer(
        long userId,
        Long materialId,
        List<MaterialChunks> materialChunks,
        List<Bm25Scorer.ChunkData> allChunkData
    ) {
        String cacheKey = bm25CacheKey(userId, materialId, materialChunks);
        Bm25IndexCacheEntry cached = bm25IndexCache.get(cacheKey);
        if (cached != null) {
            return cached.scorer();
        }
        if (bm25IndexCache.size() >= BM25_CACHE_MAX_ENTRIES) {
            bm25IndexCache.clear();
        }
        return bm25IndexCache.computeIfAbsent(
            cacheKey,
            ignored -> new Bm25IndexCacheEntry(new Bm25Scorer(List.copyOf(allChunkData)))
        ).scorer();
    }

    private String bm25CacheKey(long userId, Long materialId, List<MaterialChunks> materialChunks) {
        StringBuilder key = new StringBuilder(materialId == null ? "user:" + userId : "material:" + materialId);
        for (MaterialChunks materialChunk : materialChunks) {
            LearningMaterialEntity material = materialChunk.material();
            key.append('|')
                .append(material.getId())
                .append(':')
                .append(material.getChunkCount())
                .append(':')
                .append(material.getUpdatedAt());
            for (MaterialChunkEntity chunk : materialChunk.chunks()) {
                key.append(',')
                    .append(chunk.getId())
                    .append('@')
                    .append(chunk.getChunkIndex());
            }
        }
        return key.toString();
    }

    private List<ScoredChunk> findVectorScoredChunks(long userId, String question, Long materialId) {
        Optional<List<Double>> questionEmbedding = embeddingClient.embed(question);
        if (questionEmbedding.isEmpty() || questionEmbedding.get().isEmpty()) {
            return List.of();
        }
        double scoreThreshold = effectiveEmbeddingScoreThreshold(question);
        List<ScoredChunk> vectorStoreChunks = findVectorStoreScoredChunks(userId, materialId, questionEmbedding.get(), scoreThreshold);
        if (!vectorStoreChunks.isEmpty()) {
            return vectorStoreChunks;
        }
        List<LearningMaterialEntity> materials = materialId == null
            ? learningMaterialRepository.findByOwnerIdOrderByCreatedAtDesc(userId)
            : learningMaterialRepository.findByIdAndOwnerId(materialId, userId).stream().toList();

        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (LearningMaterialEntity material : materials) {
            if (material.getParseStatus() != MaterialParseStatus.SUCCESS) {
                continue;
            }
            for (MaterialChunkEntity chunk : materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId())) {
                List<Double> chunkEmbedding = parseEmbedding(chunk.getEmbeddingJson());
                if (chunkEmbedding.isEmpty()) {
                    continue;
                }
                double score = cosineSimilarity(questionEmbedding.get(), chunkEmbedding);
                if (Double.isNaN(score) || score < scoreThreshold) {
                    continue;
                }
                scoredChunks.add(new ScoredChunk(material, chunk, score));
            }
        }
        scoredChunks.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scoredChunks;
    }

    private List<ScoredChunk> findVectorStoreScoredChunks(long userId, Long materialId, List<Double> questionEmbedding, double scoreThreshold) {
        if (!vectorStoreClient.configured()) {
            return List.of();
        }
        List<VectorSearchResult> results = vectorStoreClient.search(
            userId,
            materialId,
            questionEmbedding,
            Math.max(embeddingProperties.topK() * 4, 20),
            scoreThreshold
        );
        if (results.isEmpty()) {
            return List.of();
        }

        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (VectorSearchResult result : results) {
            LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(result.materialId(), userId)
                .orElse(null);
            if (material == null || material.getParseStatus() != MaterialParseStatus.SUCCESS) {
                continue;
            }
            MaterialChunkEntity chunk = materialChunkRepository.findById(result.chunkId()).orElse(null);
            if (chunk == null || !material.getId().equals(chunk.getMaterialId())) {
                continue;
            }
            scoredChunks.add(new ScoredChunk(material, chunk, result.score()));
        }
        return scoredChunks;
    }

    private double effectiveEmbeddingScoreThreshold(String question) {
        double baseThreshold = embeddingProperties.scoreThreshold();
        int normalizedLength = normalizeForTermMatch(question).length();
        if (normalizedLength > 0 && normalizedLength <= 8) {
            return Math.max(0.42, baseThreshold - 0.08);
        }
        if (normalizedLength >= 80) {
            return Math.min(0.65, baseThreshold + 0.03);
        }
        return baseThreshold;
    }

    private List<ScoredChunk> selectTopChunks(List<ScoredChunk> scoredChunks) {
        List<ScoredChunk> selected = new ArrayList<>();
        Set<String> seenPages = new HashSet<>();
        for (ScoredChunk chunk : scoredChunks) {
            if (chunk.score() <= 0.0) {
                continue;
            }
            if (usesBm25Scores(scoredChunks) && chunk.score() < 1.0) {
                break;
            }
            if (selected.size() >= embeddingProperties.topK()) {
                break;
            }
            String pageKey = chunk.material().getId() + ":" + (chunk.chunk().getPageNo() == null ? "null" : chunk.chunk().getPageNo());
            if (selected.size() >= 3 && seenPages.contains(pageKey)) {
                continue;
            }
            selected.add(chunk);
            seenPages.add(pageKey);
        }
        return selected;
    }

    private boolean usesBm25Scores(List<ScoredChunk> scoredChunks) {
        return scoredChunks.stream().map(ScoredChunk::score).anyMatch(score -> score > 1.0);
    }

    private List<ScoredChunk> limitContextChunks(List<ScoredChunk> selected) {
        List<ScoredChunk> limited = new ArrayList<>();
        int totalChars = 0;
        for (ScoredChunk chunk : selected) {
            String text = cleanExcerptText(chunk.chunk().getChunkText());
            if (limited.size() >= Math.max(embeddingProperties.topK(), 6)) {
                break;
            }
            if (!limited.isEmpty() && totalChars + text.length() > MAX_CONTEXT_CHARS) {
                break;
            }
            limited.add(chunk);
            totalChars += text.length();
        }
        return limited;
    }

    private List<Double> parseEmbedding(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank() || embeddingJson.trim().startsWith("{")) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(embeddingJson);
            if (!root.isArray() || root.isEmpty()) {
                return List.of();
            }
            List<Double> embedding = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                if (!node.isNumber()) {
                    return List.of();
                }
                embedding.add(node.asDouble());
            }
            return embedding;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) {
            return Double.NaN;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.size(); index++) {
            double l = left.get(index);
            double r = right.get(index);
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return Double.NaN;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static final java.util.regex.Pattern IMAGE_MARKER_PATTERN =
        java.util.regex.Pattern.compile("\\[\\[material-image:[^\\]]+\\]\\]\\s*");
    private static final java.util.regex.Pattern IMAGE_MARKER_EXTRACT_PATTERN =
        java.util.regex.Pattern.compile("\\[\\[material-image:([^\\]]+)\\]\\]");
    private static final java.util.regex.Pattern IMAGE_OCR_PATTERN =
        java.util.regex.Pattern.compile("\\[image ocr:[^\\]]*\\]\\s*");
    private static final int MAX_IMAGES_PER_CHUNK = 3;
    private static final int MAX_IMAGES_PER_REQUEST = 5;
    private static final int MAX_IMAGE_SIDE = 768;
    private static final String ASSET_SUFFIX = ".assets";
    private static final String IMAGE_MARKER_PREFIX = "[[material-image:";
    private static final String PAGE_IMAGE_RE = "^page-(\\d+)(?:-\\d+)?\\.png$";

    private String excerpt(String text) {
        String cleaned = IMAGE_MARKER_PATTERN.matcher(text == null ? "" : text).replaceAll("");
        cleaned = IMAGE_OCR_PATTERN.matcher(cleaned).replaceAll("图片OCR：");
        String normalized = cleaned.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160) + "...";
    }

    private String excerpt(ScoredChunk chunk) {
        if (chunk == null) {
            return "";
        }
        String normalized = cleanExcerptText(chunk.chunk().getChunkText());
        if (normalized.length() <= 160) {
            return normalized;
        }
        if (chunk.highlightTerms().isEmpty()) {
            return normalized.substring(0, 160) + "...";
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        int bestStart = -1;
        int bestEnd = -1;
        int bestScore = -1;
        for (String term : chunk.highlightTerms()) {
            if (term == null || term.isBlank()) {
                continue;
            }
            String normalizedTerm = term.toLowerCase(Locale.ROOT);
            int index = lower.indexOf(normalizedTerm);
            while (index >= 0) {
                int start = Math.max(0, index - 70);
                int end = Math.min(normalized.length(), start + 180);
                if (end - start < 180) {
                    start = Math.max(0, end - 180);
                }
                String candidate = lower.substring(start, end);
                int score = countTermsInExcerpt(candidate, chunk.highlightTerms());
                if (score > bestScore || (score == bestScore && (bestStart < 0 || start < bestStart))) {
                    bestStart = start;
                    bestEnd = end;
                    bestScore = score;
                }
                index = lower.indexOf(normalizedTerm, index + normalizedTerm.length());
            }
        }
        if (bestStart < 0) {
            return normalized.substring(0, 160) + "...";
        }
        return (bestStart > 0 ? "..." : "") + normalized.substring(bestStart, bestEnd) + (bestEnd < normalized.length() ? "..." : "");
    }

    private int countTermsInExcerpt(String lowerExcerpt, List<String> terms) {
        int count = 0;
        for (String term : terms) {
            if (term != null && !term.isBlank() && lowerExcerpt.contains(term.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }

    private String cleanExcerptText(String text) {
        String cleaned = IMAGE_MARKER_PATTERN.matcher(text == null ? "" : text).replaceAll("");
        cleaned = IMAGE_OCR_PATTERN.matcher(cleaned).replaceAll("");
        return cleaned.trim().replaceAll("\\s+", " ");
    }

    private List<LlmImage> loadChunkImages(LearningMaterialEntity material, MaterialChunkEntity chunk) {
        String chunkText = chunk.getChunkText();
        if (chunkText == null || material.getStoragePath() == null) {
            return List.of();
        }
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        Path assetDir = assetDir(sourcePath);
        if (!Files.isDirectory(assetDir) && material.getSourceType() != MaterialSourceType.PDF) {
            return List.of();
        }
        List<LlmImage> images = new ArrayList<>();
        Matcher matcher = IMAGE_MARKER_EXTRACT_PATTERN.matcher(chunkText);
        int count = 0;
        while (matcher.find() && count < MAX_IMAGES_PER_CHUNK) {
            String imageName = matcher.group(1);
            Path imagePath = resolveAssetPath(sourcePath, imageName);
            if (imagePath == null && material.getSourceType() == MaterialSourceType.PDF) {
                imagePath = renderPdfPageAsset(sourcePath, imageName);
            }
            if (imagePath == null) {
                continue;
            }
            try {
                BufferedImage original = ImageIO.read(imagePath.toFile());
                if (original == null) continue;
                BufferedImage resized = resizeImage(original, MAX_IMAGE_SIDE);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(resized, "png", baos);
                String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
                images.add(new LlmImage(base64, "image/png"));
                count++;
            } catch (Exception ignored) {
            }
        }
        if (images.isEmpty() && material.getSourceType() == MaterialSourceType.PDF && chunk.getPageNo() != null) {
            LlmImage pageImage = loadImage(renderPdfPageAsset(sourcePath, "page-" + chunk.getPageNo() + ".png"));
            if (pageImage != null) {
                images.add(pageImage);
            }
        }
        return images;
    }

    private LlmImage loadImage(Path imagePath) {
        if (imagePath == null) {
            return null;
        }
        try {
            BufferedImage original = ImageIO.read(imagePath.toFile());
            if (original == null) return null;
            BufferedImage resized = resizeImage(original, MAX_IMAGE_SIDE);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resized, "png", baos);
            String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
            return new LlmImage(base64, "image/png");
        } catch (Exception ignored) {
            return null;
        }
    }

    private BufferedImage resizeImage(BufferedImage original, int maxSide) {
        int w = original.getWidth();
        int h = original.getHeight();
        if (w <= maxSide && h <= maxSide) {
            return original;
        }
        double scale = Math.min((double) maxSide / w, (double) maxSide / h);
        int newW = (int) (w * scale);
        int newH = (int) (h * scale);
        BufferedImage resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, newW, newH, null);
        g.dispose();
        return resized;
    }

    private Path assetDir(Path sourcePath) {
        return Path.of(sourcePath.toString() + ASSET_SUFFIX).toAbsolutePath().normalize();
    }

    private Path resolveStoredPath(String storagePath) {
        Path rawPath = Path.of(storagePath.trim());
        if (!rawPath.isAbsolute()) {
            return storageRoot.resolve(rawPath).normalize();
        }
        Path absolutePath = rawPath.toAbsolutePath().normalize();
        Optional<Path> remappedPath = remapToCurrentStorageRoot(absolutePath);
        if (remappedPath.isPresent() && Files.exists(remappedPath.get())) {
            return remappedPath.get();
        }
        if (absolutePath.startsWith(storageRoot)) {
            return absolutePath;
        }
        return remappedPath.orElse(absolutePath);
    }

    private Optional<Path> remapToCurrentStorageRoot(Path absolutePath) {
        Path storageRootName = storageRoot.getFileName();
        if (storageRootName == null) {
            return Optional.empty();
        }
        String rootName = storageRootName.toString();
        for (int index = 0; index < absolutePath.getNameCount() - 1; index++) {
            if (absolutePath.getName(index).toString().equalsIgnoreCase(rootName)) {
                Path relativePath = absolutePath.subpath(index + 1, absolutePath.getNameCount());
                Path remappedPath = storageRoot.resolve(relativePath).normalize();
                if (remappedPath.startsWith(storageRoot)) {
                    return Optional.of(remappedPath);
                }
            }
        }
        return Optional.empty();
    }

    private Path resolveAssetPath(Path sourcePath, String fileName) {
        Path dir = assetDir(sourcePath);
        Path direct = dir.resolve(fileName).normalize();
        if (direct.startsWith(dir) && Files.exists(direct) && Files.isRegularFile(direct)) {
            return direct;
        }

        return null;
    }

    private Path renderPdfPageAsset(Path sourcePath, String fileName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile(PAGE_IMAGE_RE)
            .matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        int pageNo = Integer.parseInt(matcher.group(1));
        Path dir = assetDir(sourcePath);
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir)) {
            return null;
        }
        try {
            Files.createDirectories(dir);
            try (var document = Loader.loadPDF(sourcePath.toFile())) {
                if (pageNo < 1 || pageNo > document.getNumberOfPages()) {
                    return null;
                }
                PDFRenderer renderer = new PDFRenderer(document);
                BufferedImage image = renderer.renderImageWithDPI(pageNo - 1, 144);
                ImageIO.write(image, "png", target.toFile());
                return target;
            }
        } catch (Exception exception) {
            return null;
        }
    }

    private void appendImages(List<LlmImage> target, List<LlmImage> additions) {
        for (LlmImage image : additions) {
            if (target.size() >= MAX_IMAGES_PER_REQUEST) {
                return;
            }
            target.add(image);
        }
    }

    private String detectMediaType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".tiff") || lower.endsWith(".tif")) return "image/tiff";
        return "image/png";
    }

    private void collectImagesFromMaterialChunks(long userId, Long materialId, List<ScoredChunk> selectedChunks, List<LlmImage> images) {
        Set<Long> selectedChunkIds = selectedChunks.stream()
            .map(c -> c.chunk().getId())
            .collect(Collectors.toSet());
        Set<Long> materialIds = selectedChunks.stream()
            .map(c -> c.material().getId())
            .collect(Collectors.toSet());
        if (materialId != null) {
            materialIds.add(materialId);
        }
        for (Long matId : materialIds) {
            if (images.size() >= MAX_IMAGES_PER_REQUEST) break;
            LearningMaterialEntity mat = learningMaterialRepository.findById(matId).orElse(null);
            if (mat == null || mat.getStoragePath() == null) continue;
            for (MaterialChunkEntity chunk : materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(matId)) {
                if (images.size() >= MAX_IMAGES_PER_REQUEST) break;
                if (selectedChunkIds.contains(chunk.getId())) continue;
                if (chunk.getChunkText() == null || !chunk.getChunkText().contains(IMAGE_MARKER_PREFIX)) continue;
                appendImages(images, loadChunkImages(mat, chunk));
            }
        }
    }

    private enum KeywordIntent {
        DEFINITION,
        FUNCTION,
        COMPARISON,
        OCCURRENCE
    }

    private record KeywordQuery(List<String> terms, KeywordIntent intent) {
    }

    private record MaterialChunks(LearningMaterialEntity material, List<MaterialChunkEntity> chunks) {
    }

    private record Bm25IndexCacheEntry(Bm25Scorer scorer) {
    }

    private record RetrievalCacheEntry(List<CachedScoredChunk> chunks) {
    }

    private record CachedScoredChunk(Long chunkId, double score, List<String> highlightTerms) {
    }

    private record TermCoverage(double score, List<String> missingTerms) {
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
    }

    private record ScoredChunk(
        LearningMaterialEntity material,
        MaterialChunkEntity chunk,
        double score,
        List<String> highlightTerms
    ) {
        private ScoredChunk(LearningMaterialEntity material, MaterialChunkEntity chunk, double score) {
            this(material, chunk, score, List.of());
        }
    }
}
