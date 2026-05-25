package com.mytext.learningassistant.rag;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.mytext.learningassistant.common.BusinessException;
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

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagService {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
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
    private final UserFavoriteRepository userFavoriteRepository;
    private final MaterialSummaryRepository materialSummaryRepository;
    private final ThirdPartyLlmClient thirdPartyLlmClient;
    private final Path storageRoot;

    public RagService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        RagQuestionRepository ragQuestionRepository,
        RagQuestionSourceRepository ragQuestionSourceRepository,
        UserFavoriteRepository userFavoriteRepository,
        MaterialSummaryRepository materialSummaryRepository,
        ThirdPartyLlmClient thirdPartyLlmClient,
        @Value("${app.storage-dir:${user.dir}/target/learning-assistant-files}") String storageDir
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.ragQuestionRepository = ragQuestionRepository;
        this.ragQuestionSourceRepository = ragQuestionSourceRepository;
        this.userFavoriteRepository = userFavoriteRepository;
        this.materialSummaryRepository = materialSummaryRepository;
        this.thirdPartyLlmClient = thirdPartyLlmClient;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Transactional
    public RagChatResponse chat(long userId, ChatRequest request) {
        boolean generalChat = isGeneralChat(request);
        if (isMaterialChat(request) || (!generalChat && request.materialId() != null)) {
            validateCurrentMaterialForChat(userId, request.materialId());
        }
        List<ScoredChunk> selectedChunks = selectContextChunks(userId, request, generalChat);

        List<String> excerpts = buildExcerpts(request, selectedChunks);
        List<LlmImage> images = new ArrayList<>(selectedChunks.stream()
            .flatMap(chunk -> loadChunkImages(chunk.material(), chunk.chunk()).stream())
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

        LlmCompletion rawCompletion = answerWithThirdParty(request.question(), excerpts, images, generalChat, request.answerStyle())
            .orElseGet(() -> new LlmCompletion(
                generalChat
                    ? buildGeneralFallbackAnswer(request.question())
                    : buildCleanAnswer(request.question(), selectedChunks, request.answerStyle()),
                "local-rag-demo"
            ));
        LlmCompletion completion = new LlmCompletion(
            generalChat
                ? decorateGeneralAnswer(rawCompletion.content())
                : decorateAnswer(request.question(), rawCompletion.content(), selectedChunks, request.answerStyle()),
            rawCompletion.modelName()
        );

        RagQuestionEntity question = new RagQuestionEntity();
        question.setUserId(userId);
        question.setQuestionText(request.question());
        question.setTitle(buildConversationTitle(request.question()));
        question.setAnswerText(completion.content());
        question.setModelName(completion.modelName());
        question.setQuestionStatus(QuestionStatus.SUCCESS);
        RagQuestionEntity savedQuestion = ragQuestionRepository.save(question);

        List<RagQuestionSourceEntity> sourceEntities = saveSources(savedQuestion.getId(), selectedChunks);

        return new RagChatResponse(
            savedQuestion.getId(),
            savedQuestion.getQuestionText(),
            savedQuestion.getAnswerText(),
            sourceEntities.stream().map(this::toSourceResponse).toList(),
            savedQuestion.getCreatedAt() == null ? null : savedQuestion.getCreatedAt().format(DATETIME_FORMATTER)
        );
    }

    private java.util.Optional<LlmCompletion> answerWithThirdParty(
        String question,
        List<String> excerpts,
        List<LlmImage> images,
        boolean generalChat,
        String answerStyle
    ) {
        java.util.Optional<LlmCompletion> completion = isHomeworkStyle(answerStyle)
            ? thirdPartyLlmClient.answer(question, excerpts, images, generalChat, answerStyle)
            : thirdPartyLlmClient.answer(question, excerpts, images, generalChat);
        if (completion != null && completion.isPresent()) {
            return completion;
        }
        return completion == null ? java.util.Optional.empty() : completion;
    }

    @Transactional
    public RagStreamResult chatStream(long userId, ChatRequest request, java.util.function.Consumer<String> onChunk) {
        boolean generalChat = isGeneralChat(request);
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

        List<LlmImage> images = new ArrayList<>(selectedChunks.stream()
            .flatMap(chunk -> loadChunkImages(chunk.material(), chunk.chunk()).stream())
            .limit(MAX_IMAGES_PER_REQUEST)
            .toList());

        if (images.size() < MAX_IMAGES_PER_REQUEST && !generalChat) {
            collectImagesFromMaterialChunks(userId, request.materialId(), selectedChunks, images);
        }

        String questionWithContext = buildQuestionWithHistory(request.question(), request.history());

        String answer = isHomeworkStyle(request.answerStyle())
            ? thirdPartyLlmClient.answerStream(questionWithContext, excerpts, images, onChunk, generalChat, request.answerStyle())
            : thirdPartyLlmClient.answerStream(questionWithContext, excerpts, images, onChunk, generalChat);
        if (answer.isBlank()) {
            answer = generalChat
                ? buildGeneralFallbackAnswer(request.question())
                : buildCleanAnswer(request.question(), selectedChunks, request.answerStyle());
        }

        String decoratedAnswer = generalChat
            ? decorateGeneralAnswer(answer)
            : decorateAnswer(request.question(), answer, selectedChunks, request.answerStyle());

        Long questionId = saveStreamResult(userId, request.question(), decoratedAnswer, selectedChunks);

        List<RagSourceResponse> sources = selectedChunks.stream()
            .map(this::toSourceResponse)
            .toList();

        return new RagStreamResult(questionId, decoratedAnswer, sources);
    }

    private List<ScoredChunk> selectContextChunks(long userId, ChatRequest request, boolean generalChat) {
        if (generalChat || (request.selectedText() != null && !request.selectedText().isBlank())) {
            return List.of();
        }
        List<ScoredChunk> keywordChunks = findKeywordChunks(userId, request.question(), request.materialId());
        if (!keywordChunks.isEmpty()) {
            return keywordChunks;
        }
        if (request.chunkId() == null) {
            List<ScoredChunk> topChunks = selectTopChunks(findScoredChunks(userId, request.question(), request.materialId()));
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
            List<ScoredChunk> topChunks = selectTopChunks(findScoredChunks(userId, request.question(), request.materialId()));
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
        appendUniqueChunks(selected, seenChunkIds, currentPageChunks(currentChunks.get(0)));
        appendUniqueChunks(selected, seenChunkIds, currentSectionChunks(currentChunks.get(0)));
        if (isLocalContextQuestion(request.question())) {
            return selected.stream().limit(8).toList();
        }
        appendUniqueChunks(
            selected,
            seenChunkIds,
            selectTopChunks(findScoredChunks(userId, request.question(), currentChunks.get(0).material().getId()))
        );
        return selected.stream().limit(6).toList();
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
            .filter(chunk -> containsAnyTerm(chunk.getChunkText(), normalizedTerms))
            .map(chunk -> new ScoredChunk(
                material,
                chunk,
                keywordScore(chunk.getChunkText(), normalizedTerms, query.intent()),
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

    private Long saveStreamResult(long userId, String questionText, String answer, List<ScoredChunk> chunks) {
        try {
            RagQuestionEntity question = new RagQuestionEntity();
            question.setUserId(userId);
            question.setQuestionText(questionText);
            question.setTitle(buildConversationTitle(questionText));
            question.setAnswerText(answer);
            question.setModelName("stream");
            question.setQuestionStatus(QuestionStatus.SUCCESS);
            RagQuestionEntity saved = ragQuestionRepository.save(question);
            saveSources(saved.getId(), chunks);
            return saved.getId();
        } catch (Exception ignored) {
            return 0L;
        }
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

    private String buildQuestionWithHistory(String question, List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return question;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("对话历史：\n");
        int start = Math.max(0, history.size() - 10);
        for (int i = start; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            sb.append(msg.role()).append("：").append(msg.content()).append("\n");
        }
        sb.append("\n当前问题：").append(question);
        return sb.toString();
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
        return ragQuestionRepository.findByUserIdOrderByPinnedDescCreatedAtDesc(userId).stream()
            .map(question -> toHistoryItem(userId, question))
            .toList();
    }

    @Transactional(readOnly = true)
    public RagHistoryDetailResponse historyDetail(long userId, long questionId) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "Question not found"));
        List<RagSourceResponse> sources = ragQuestionSourceRepository.findByQuestionIdOrderByRankScoreDesc(questionId).stream()
            .map(this::toSourceResponse)
            .toList();
        return toHistoryDetail(userId, question, sources);
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
    public void deleteHistory(long userId, long questionId) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "Question not found"));
        userFavoriteRepository.deleteByUserIdAndQuestionId(userId, questionId);
        ragQuestionSourceRepository.deleteByQuestionId(questionId);
        ragQuestionRepository.delete(question);
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
            question.getQuestionText(),
            question.getAnswerText(),
            saved.getCreatedAt() == null ? null : saved.getCreatedAt().format(DATETIME_FORMATTER)
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
                    question.getQuestionText(),
                    question.getAnswerText(),
                    favorite.getCreatedAt() == null ? null : favorite.getCreatedAt().format(DATETIME_FORMATTER)
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
        List<RagSourceResponse> sources
    ) {
        UserFavoriteEntity favorite = userFavoriteRepository.findByUserIdAndQuestionId(userId, question.getId())
            .orElse(null);
        return new RagHistoryDetailResponse(
            question.getId(),
            question.getTitle(),
            question.getQuestionText(),
            question.getAnswerText(),
            question.getCreatedAt() == null ? null : question.getCreatedAt().format(DATETIME_FORMATTER),
            sources,
            favorite == null ? null : favorite.getId(),
            favorite != null,
            question.isPinned()
        );
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

    private String decorateAnswer(String question, String content, List<ScoredChunk> selectedChunks, String answerStyle) {
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
        return content.trim()
            .replaceAll("\\*\\*([^*]+)\\*\\*", "$1")
            .replaceAll("\\*([^*\\n]+)\\*", "$1")
            .replaceAll("(?m)^\\s*\\*\\s+", "")
            .replaceAll("(?m)^\\s*-\\s+", "")
            .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
            .replace("*", "")
            .replaceAll("[ \\t]+\\n", "\n")
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
        List<LearningMaterialEntity> validMaterials = new ArrayList<>();
        Map<Long, LearningMaterialEntity> chunkMaterialMap = new LinkedHashMap<>();
        for (LearningMaterialEntity material : materials) {
            if (material.getParseStatus() != MaterialParseStatus.SUCCESS) {
                continue;
            }
            validMaterials.add(material);
            for (MaterialChunkEntity chunk : materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId())) {
                allChunkData.add(new Bm25Scorer.ChunkData(chunk.getId(), chunk.getChunkText()));
                chunkMaterialMap.put(chunk.getId(), material);
            }
        }

        Bm25Scorer scorer = new Bm25Scorer(allChunkData);
        Set<String> questionTokens = scorer.tokenize(question);

        List<ScoredChunk> scoredChunks = new ArrayList<>();
        for (LearningMaterialEntity material : validMaterials) {
            for (MaterialChunkEntity chunk : materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId())) {
                double score = scorer.score(questionTokens, chunk.getId());
                scoredChunks.add(new ScoredChunk(material, chunk, score));
            }
        }
        scoredChunks.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return scoredChunks;
    }

    private List<ScoredChunk> selectTopChunks(List<ScoredChunk> scoredChunks) {
        List<ScoredChunk> selected = new ArrayList<>();
        Set<String> seenPages = new HashSet<>();
        for (ScoredChunk chunk : scoredChunks) {
            if (chunk.score() < 1.0) {
                break;
            }
            if (selected.size() >= 5) {
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
