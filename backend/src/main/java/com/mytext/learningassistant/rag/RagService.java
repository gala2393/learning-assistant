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
import java.util.LinkedHashSet;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG（检索增强生成）核心业务服务。
 * <p>
 * 这是整个智学引擎系统最核心的类，负责将用户的提问与上传的学习资料进行智能匹配，
 * 再借助大语言模型（LLM）生成高质量的回答。
 * <p>
 * <h3>核心 RAG 流程概述：</h3>
 * <ol>
 *   <li><b>查询理解</b>：分析用户问题意图（定义、对比、功能等），提取关键词</li>
 *   <li><b>多路检索</b>：同时执行向量语义检索 + BM25 关键词检索 + 摘要种子检索</li>
 *   <li><b>混合融合与重排</b>：将多路检索结果按权重融合，再通过 Reranker 精排</li>
 *   <li><b>上下文构建</b>：选出最优片段，组装成 LLM 能理解的上下文</li>
 *   <li><b>LLM 生成</b>：调用大模型生成回答，支持普通模式和流式模式</li>
 *   <li><b>后处理</b>：装饰回答（添加引用依据）、记录使用日志、保存问答历史</li>
 * </ol>
 * <p>
 * <h3>其他功能：</h3>
 * <ul>
 *   <li>通用聊天模式（不依赖资料的自由问答）</li>
 *   <li>资料摘要生成</li>
 *   <li>问答历史管理（收藏、置顶、删除）</li>
 *   <li>RAG 质量评估（忠实度、相关性评分）</li>
 *   <li>评估套件（批量测试用例 + 定时评估）</li>
 *   <li>图片多模态支持（从资料中提取图片发送给视觉模型）</li>
 * </ul>
 *
 * @see ThirdPartyLlmClient 大语言模型调用客户端
 * @see EmbeddingClient 向量嵌入客户端
 * @see RerankerClient 重排序客户端
 * @see VectorStoreClient 向量存储客户端
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    // ========== 常量定义 ==========

    /** 日期时间格式化器，用于将 LocalDateTime 格式化为 "yyyy-MM-dd HH:mm:ss" */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** JSON 序列化/反序列化工具，用于解析嵌入向量、评估结果等 JSON 数据 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 资料片段上下文的最大字符数，避免 RAG 资料把模型输入撑得过长。 */
    private static final int MAX_CONTEXT_CHARS = 14_000;

    /** 通用问答最近原文窗口，20 条约等于 10 轮对话。 */
    private static final int GENERAL_RECENT_HISTORY_ITEMS = 20;

    /** 资料问答最近原文窗口，16 条约等于 8 轮对话，给资料片段预留更多预算。 */
    private static final int MATERIAL_RECENT_HISTORY_ITEMS = 16;

    /** 通用问答历史原文总字符预算。 */
    private static final int GENERAL_HISTORY_CHAR_BUDGET = 16_000;

    /** 资料问答历史原文总字符预算，避免和资料片段叠加后请求过大。 */
    private static final int MATERIAL_HISTORY_CHAR_BUDGET = 12_000;

    /** 用户消息进入上下文的单条长度上限。 */
    private static final int MAX_HISTORY_USER_CHARS = 1_500;

    /** AI 回答进入上下文的单条长度上限。 */
    private static final int MAX_HISTORY_ASSISTANT_CHARS = 2_000;

    /** 长期会话摘要进入模型输入的最大字符数。 */
    private static final int MAX_MEMORY_PROMPT_CHARS = 5_000;

    /** 数据库中保存的长期会话摘要最大字符数。 */
    private static final int MAX_MEMORY_STORAGE_CHARS = 8_000;

    /** 选中文本直接作为资料依据时的最大字符数。 */
    private static final int MAX_SELECTED_TEXT_CONTEXT_CHARS = 8_000;

    /** 至少保留这些最近问答为原文，较早内容才会滚动进入摘要。 */
    private static final int MEMORY_RECENT_QUESTION_WINDOW = 8;

    /** 普通单次回答大约能稳定承载的中文正文长度，超过后提示用户输入"继续"分段续写。 */
    private static final int LONG_DOCUMENT_PART_TARGET_CHARS = 2_800;

    /** 未写明字数但明确要求"超长文档"时使用的默认目标长度。 */
    private static final int DEFAULT_LONG_DOCUMENT_TARGET_CHARS = 9_000;

    /** BM25 索引缓存的最大条目数，避免内存无限增长 */
    private static final int BM25_CACHE_MAX_ENTRIES = 64;

    /** 检索结果缓存的最大条目数，对相同查询复用检索结果以提高响应速度 */
    private static final int RETRIEVAL_CACHE_MAX_ENTRIES = 128;

    /** 普通用户每天调用系统默认模型的聊天次数上限（管理员和自定义模型用户不受此限制） */
    private static final int USER_DAILY_CHAT_LIMIT = 50;

    /** 每次请求用户最多上传的图片数量 */
    private static final int MAX_USER_IMAGES_PER_REQUEST = 8;

    /** 单张用户图片 Base64 编码的最大字符数（约 2MB 图片大小） */
    private static final int MAX_USER_IMAGE_BASE64_CHARS = 2_800_000;

    /** 结构化总结发送给模型的最大字符数，避免长资料撑爆上下文 */
    private static final int MAX_SUMMARY_INPUT_CHARS = 32_000;

    /** 扫描版资料生成总结时最多附带的页面图片数，和普通问答图片上限分开控制。 */
    private static final int MAX_SUMMARY_IMAGES_PER_REQUEST = 20;
    // ========== 正则匹配模式：用于问题意图识别 ==========

    /**
     * "局部上下文问题"的正则匹配模式。
     * 用于判断用户是否在问"这一页讲什么"、"当前内容"、"本章"等与当前阅读位置相关的问题。
     * 匹配时会优先使用当前页/章节的片段，而不是全局检索。
     */
    private static final Pattern LOCAL_CONTEXT_QUESTION_PATTERN = Pattern.compile(
        "(?i)(this|current|page|chapter|section|paragraph|chunk|slide|这里|这页|这一页|本页|当前页|这个页面|这章|这一章|本章|当前章节|这一节|本节|当前内容|这段|这一段|这部分|这里面|讲什么|说什么|主要内容|总结一下|概括)"
    );
    /**
     * "资料概览问题"的正则匹配模式。
     * 用于判断用户是否在问"这是什么书"、"介绍一下"、"概括"等关于整份资料的问题。
     * 匹配时会返回资料的前几个片段作为概览上下文。
     */
    private static final Pattern MATERIAL_OVERVIEW_QUESTION_PATTERN = Pattern.compile(
        "(?i)(what\\s+(?:is|does).*?(?:book|document|material)|summari[sz]e|overview|introduce|about|"
            + "\\u8fd9(?:\\u662f)?\\u4ec0\\u4e48(?:\\u4e66|\\u8d44\\u6599|\\u6587\\u6863|\\u6587\\u4ef6)|"
            + "\\u8fd9\\u672c\\u4e66|\\u8fd9\\u4efd(?:\\u8d44\\u6599|\\u6587\\u6863|\\u6587\\u4ef6)|"
            + "\\u8bb2\\u4ec0\\u4e48|\\u4ecb\\u7ecd\\u4e00\\u4e0b|\\u7b80\\u4ecb|\\u6982\\u62ec|\\u4e3b\\u8981\\u5185\\u5bb9)"
    );
    /**
     * "术语定义问题"的正则匹配模式。
     * 用于提取"什么是 XXX"、"定义 XXX"、"what is XXX"等定义类问题中的核心术语。
     * 通过捕获组(1)提取术语名称，用于后续精确的关键词检索。
     */
    private static final Pattern TERM_DEFINITION_PATTERN = Pattern.compile(
        "(?i)(?:什么是|何为|解释一下|解释|定义|含义|概念|define|definition of|what is|what are)\\s*[\"“'‘]?([^\"”'’？?，,。；;：:\\n]{2,80})[\"”'’]?"
    );

    /**
     * 长文档字数识别模式。
     * 支持"10000字"、"12000 个字"、"8000字符"、"1万字"这类写法，用来判断是否需要自动分段生成。
     */
    private static final Pattern LONG_DOCUMENT_CHAR_COUNT_PATTERN = Pattern.compile(
        "(?i)(\\d+(?:\\.\\d+)?)\\s*(万)?\\s*(?:个)?\\s*(?:字|字符|汉字|中文字符|words?)"
    );

    // ========== 依赖注入：数据访问层 ==========

    /** 学习资料仓库，用于查询用户上传的资料信息 */
    private final LearningMaterialRepository learningMaterialRepository;

    /** 资料片段仓库，用于查询被切分后的资料块（chunk） */
    private final MaterialChunkRepository materialChunkRepository;

    /** RAG 问答记录仓库，保存每次问答的问题和回答 */
    private final RagQuestionRepository ragQuestionRepository;

    /** 问答来源仓库，保存每个回答引用的资料片段来源 */
    private final RagQuestionSourceRepository ragQuestionSourceRepository;

    /** 会话长期记忆仓库，用于保存滑出最近窗口的问答摘要。 */
    private final RagConversationMemoryRepository ragConversationMemoryRepository;

    /** 用户反馈仓库，保存用户对回答的评价（点赞/踩） */
    private final RagFeedbackRepository ragFeedbackRepository;

    /** 单条评估仓库，保存单次问答的质量评估结果 */
    private final RagEvaluationRepository ragEvaluationRepository;

    /** 评估套件仓库，管理批量评估测试套件 */
    private final RagEvaluationSuiteRepository ragEvaluationSuiteRepository;

    /** 评估套件用例仓库，保存套件中的测试用例 */
    private final RagEvaluationSuiteCaseRepository ragEvaluationSuiteCaseRepository;

    /** 评估套件运行记录仓库，保存每次套件运行的结果 */
    private final RagEvaluationSuiteRunRepository ragEvaluationSuiteRunRepository;

    /** 用户收藏仓库，管理用户收藏的问答记录 */
    private final UserFavoriteRepository userFavoriteRepository;

    /** 资料摘要仓库，保存资料的自动/手动生成摘要 */
    private final MaterialSummaryRepository materialSummaryRepository;

    /** 用户仓库，用于查询用户角色（判断是否为管理员） */
    private final UserRepository userRepository;

    /** 使用记录仓库，用于记录 Token 消耗等操作日志 */
    private final UsageRecordRepository usageRecordRepository;

    // ========== 依赖注入：AI 能力层 ==========

    /** 第三方大语言模型客户端，负责调用 LLM 生成回答 */
    private final ThirdPartyLlmClient thirdPartyLlmClient;

    /** 向量嵌入客户端，负责将文本转换为向量表示 */
    private final EmbeddingClient embeddingClient;

    /** 向量嵌入配置属性（topK、scoreThreshold 等） */
    private final EmbeddingProperties embeddingProperties;

    /** 重排序客户端，对检索结果进行精排（cross-encoder reranking） */
    private final RerankerClient rerankerClient;

    /** 向量存储客户端，用于外部向量数据库的检索（如 Milvus、Qdrant 等） */
    private final VectorStoreClient vectorStoreClient;

    /** 查询扩展配置属性（是否启用 HyDE、扩展查询数量等） */
    private final QueryExpansionProperties queryExpansionProperties;

    // ========== 内部状态 ==========

    /** 文件存储根目录，资料文件和资源文件的存放位置 */
    private final Path storageRoot;

    /**
     * BM25 索引缓存。
     * key = 用户/资料ID + 资料元数据摘要，value = 预构建的 BM25 评分器。
     * 避免每次查询都重建倒排索引。
     */
    private final ConcurrentMap<String, Bm25IndexCacheEntry> bm25IndexCache = new ConcurrentHashMap<>();

    /**
     * 检索结果缓存。
     * key = 用户/资料ID + 问题哈希 + 资料元数据，value = 上次检索的排序结果。
     * 当用户短时间内重复提问相似问题时可直接复用。
     */
    private final ConcurrentMap<String, RetrievalCacheEntry> retrievalResultCache = new ConcurrentHashMap<>();

    /**
     * 构造函数，通过 Spring 依赖注入初始化所有必要的组件。
     *
     * @param learningMaterialRepository 学习资料仓库
     * @param materialChunkRepository    资料片段仓库
     * @param ragQuestionRepository      问答记录仓库
     * @param ragQuestionSourceRepository 问答来源仓库
     * @param ragConversationMemoryRepository 会话长期记忆仓库
     * @param ragFeedbackRepository      用户反馈仓库
     * @param ragEvaluationRepository    评估结果仓库
     * @param ragEvaluationSuiteRepository 评估套件仓库
     * @param ragEvaluationSuiteCaseRepository 评估用例仓库
     * @param ragEvaluationSuiteRunRepository 评估运行记录仓库
     * @param userFavoriteRepository     用户收藏仓库
     * @param materialSummaryRepository  资料摘要仓库
     * @param userRepository             用户仓库
     * @param usageRecordRepository      使用记录仓库
     * @param thirdPartyLlmClient        大语言模型客户端
     * @param embeddingClient            向量嵌入客户端
     * @param embeddingProperties        嵌入配置属性
     * @param rerankerClient             重排序客户端
     * @param vectorStoreClient          向量存储客户端
     * @param queryExpansionProperties   查询扩展配置
     * @param storageDir                 文件存储目录路径（从配置文件读取）
     */
    public RagService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        RagQuestionRepository ragQuestionRepository,
        RagQuestionSourceRepository ragQuestionSourceRepository,
        RagConversationMemoryRepository ragConversationMemoryRepository,
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
        this.ragConversationMemoryRepository = ragConversationMemoryRepository;
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

    // ========== 核心 RAG 问答接口 ==========

    /**
     * RAG 普通问答（非流式）——核心入口方法。
     * <p>
     * 完整的 RAG 流程：
     * <ol>
     *   <li>检查用户今日问答次数是否超限</li>
     *   <li>判断聊天模式（通用/资料/模型身份）</li>
     *   <li>从学习资料中检索最相关的片段（检索阶段）</li>
     *   <li>收集相关图片（多模态支持）</li>
     *   <li>构建上下文并调用 LLM 生成回答（生成阶段）</li>
     *   <li>装饰回答、保存问答记录、记录使用日志</li>
     * </ol>
     *
     * @param userId  当前登录用户的 ID
     * @param request 问答请求，包含问题文本、资料ID、聊天模式、对话历史等
     * @return 问答响应，包含回答文本、来源引用、对话 ID 等
     */
    @Transactional
    public RagChatResponse chat(long userId, ChatRequest request) {
        // 第一步：检查用户今日问答次数是否已用完
        ensureChatUsageAvailable(userId);
        boolean generalChat = isGeneralChat(request);
        Long conversationId = resolveConversationId(userId, request.conversationId());
        // 如果用户问的是"你是什么模型"，走专门的身份回答逻辑
        if (thirdPartyLlmClient.isModelIdentityQuestion(request.question())) {
            return chatModelIdentity(userId, request);
        }
        // 如果是资料模式的问答，验证资料是否存在且已解析成功
        if (isMaterialChat(request) || (!generalChat && request.materialId() != null)) {
            validateCurrentMaterialForChat(userId, request.materialId());
        }

        // ===== 检索阶段：从资料中检索与问题最相关的片段 =====
        List<ScoredChunk> selectedChunks = selectContextChunks(userId, request, generalChat);

        // ===== 上下文构建阶段：将检索到的片段组装为 LLM 可理解的上下文文本 =====
        List<String> excerpts = buildExcerpts(request, selectedChunks);

        // ===== 图片收集阶段：收集用户上传的图片 + 资料中的嵌入图片 =====
        List<LlmImage> images = new ArrayList<>(userImages(request));
        images.addAll(selectedChunks.stream()
            .flatMap(chunk -> loadChunkImages(chunk.material(), chunk.chunk()).stream())
            .limit(Math.max(0, MAX_IMAGES_PER_REQUEST - images.size()))
            .toList());
        images = new ArrayList<>(images.stream()
            .limit(MAX_IMAGES_PER_REQUEST)
            .toList());

        // 如果用户正在阅读某个具体片段，也加载该片段的图片
        if (request.chunkId() != null && images.size() < MAX_IMAGES_PER_REQUEST) {
            for (ScoredChunk currentChunk : findChunksById(userId, request)) {
                appendImages(images, loadChunkImages(currentChunk.material(), currentChunk.chunk()));
            }
        }

        // 如果图片数量还不够，从资料的其他片段中补充图片
        if (images.size() < MAX_IMAGES_PER_REQUEST && !generalChat) {
            collectImagesFromMaterialChunks(userId, request.materialId(), selectedChunks, images);
        }

        // ===== 生成阶段：调用 LLM 生成回答 =====
        LongDocumentContinuation continuation = resolveLongDocumentContinuation(userId, conversationId, request.question());
        String effectiveQuestion = continuation == null ? request.question() : continuation.originalQuestion();
        String questionWithContext = buildQuestionWithHistory(userId, conversationId, request.question(), request.history(), generalChat);
        String llmQuestion = thirdPartyLlmClient.isModelIdentityQuestion(request.question())
            ? request.question()
            : continuation == null ? questionWithContext : continuationQuestionWithHistory(questionWithContext, continuation);
        // 调用第三方 LLM 生成回答；超长文档每次只生成一段，用户输入"继续"后再续写下一段。
        LlmCompletion rawCompletion = answerWithLongDocumentSupport(
            userId,
            effectiveQuestion,
            llmQuestion,
            excerpts,
            images,
            generalChat,
            request.answerStyle(),
            continuation,
            null
        )
            .orElseGet(() -> new LlmCompletion(
                generalChat
                    ? buildGeneralFallbackAnswer(request.question())
                    : buildCleanAnswer(request.question(), selectedChunks, request.answerStyle()),
                "local-rag-demo"
            ));
        // 计算 Token 消耗量（优先使用模型返回的实际值，否则估算）
        TokenUsage usage = completionUsage(rawCompletion, llmQuestion, rawCompletion.content());
        // ===== 后处理阶段：装饰回答（添加引用依据等） =====
        boolean longDocumentAnswer = longDocumentPlan(effectiveQuestion).parts() > 1;
        LlmCompletion completion = new LlmCompletion(
            generalChat
                ? decorateGeneralAnswer(rawCompletion.content())
                : longDocumentAnswer ? decorateLongDocumentAnswer(rawCompletion.content()) : decorateAnswer(request, rawCompletion.content(), selectedChunks),
            rawCompletion.modelName(),
            usage.promptTokens(),
            usage.completionTokens(),
            usage.totalTokens(),
            rawCompletion.customModel()
        );

        // ===== 持久化阶段：保存问答记录和来源信息 =====
        RagQuestionEntity question = new RagQuestionEntity();
        question.setUserId(userId);
        question.setConversationId(conversationId);
        question.setMaterialId(materialQuestionId(request));
        question.setQuestionText(request.question());
        question.setQuestionImagesJson(questionImagesJson(request));
        question.setQuestionTemporaryMaterialJson(questionTemporaryMaterialJson(request));
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
        updateConversationMemory(userId, savedQuestion.getConversationId());

        // 保存本次回答引用的来源片段
        List<RagQuestionSourceEntity> sourceEntities = saveSources(savedQuestion.getId(), selectedChunks);
        // 记录使用日志（用于 Token 消耗统计和审计）
        recordUsageLog(userId, "RAG_CHAT", savedQuestion.getId(), request, completion);

        // 构建并返回响应
        return new RagChatResponse(
            savedQuestion.getId(),
            savedQuestion.getConversationId(),
            savedQuestion.getQuestionText(),
            savedQuestion.getAnswerText(),
            sourceEntities.stream().map(this::toSourceResponse).toList(),
            savedQuestion.getCreatedAt() == null ? null : savedQuestion.getCreatedAt().format(DATETIME_FORMATTER)
        );
    }

    /**
     * 处理"你是什么模型"类的身份查询问题。
     * 直接调用 LLM 客户端获取当前模型信息，不走 RAG 检索流程。
     *
     * @param userId  用户 ID
     * @param request 问答请求
     * @return 包含模型身份信息的问答响应
     */
    private RagChatResponse chatModelIdentity(long userId, ChatRequest request) {
        Long conversationId = resolveConversationId(userId, request.conversationId());
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
        question.setConversationId(conversationId);
        question.setMaterialId(materialQuestionId(request));
        question.setQuestionText(request.question());
        question.setQuestionImagesJson(questionImagesJson(request));
        question.setQuestionTemporaryMaterialJson(questionTemporaryMaterialJson(request));
        question.setTitle(buildConversationTitle(request.question()));
        question.setAnswerText(completion.content());
        question.setModelName(completion.modelName());
        question.setPromptTokens(completion.promptTokens());
        question.setCompletionTokens(completion.completionTokens());
        question.setTotalTokens(completion.totalTokens());
        question.setCustomModel(completion.customModel());
        question.setQuestionStatus(QuestionStatus.SUCCESS);
        RagQuestionEntity savedQuestion = ensureConversationId(ragQuestionRepository.save(question));
        updateConversationMemory(userId, savedQuestion.getConversationId());
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

    /**
     * 调用第三方大语言模型生成回答。
     * <p>
     * 优先使用用户自定义模型配置；否则使用系统默认模型。
     * 对于作业模式（HOMEWORK）的回答风格，会传递额外参数。
     *
     * @param userId      用户 ID（用于查找自定义模型配置）
     * @param question    带有历史上下文的完整问题文本
     * @param excerpts    从资料中检索到的上下文片段列表
     * @param images      用户上传和资料中提取的图片列表
     * @param generalChat 是否为通用聊天模式
     * @param answerStyle 回答风格（如 "STUDY"、"HOMEWORK"）
     * @return LLM 生成结果，调用失败时返回 empty
     */
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

    /**
     * 带超长文档支持的模型调用入口。
     * <p>
     * 普通问答仍然只调用一次模型；当用户明确要求写长文档、长报告、论文等，且目标字数超过单次稳定输出能力时，
     * 后端每次只生成一个安全长度的段落，并在结尾提示用户输入"继续"生成下一段。
     *
     * @param originalQuestion 用户原始问题，用于识别目标字数和长文意图
     * @param llmQuestion      已加入长期记忆和最近对话的模型问题
     * @param continuation     长文续写状态；普通长文首段为 null
     * @param onChunk          流式回调；非流式调用传 null
     */
    private java.util.Optional<LlmCompletion> answerWithLongDocumentSupport(
        long userId,
        String originalQuestion,
        String llmQuestion,
        List<String> excerpts,
        List<LlmImage> images,
        boolean generalChat,
        String answerStyle,
        LongDocumentContinuation continuation,
        java.util.function.Consumer<String> onChunk
    ) {
        LongDocumentPlan plan = longDocumentPlan(originalQuestion);
        if (plan.parts() <= 1) {
            if (onChunk == null) {
                return answerWithThirdParty(userId, llmQuestion, excerpts, images, generalChat, answerStyle);
            }
            String answer = thirdPartyLlmClient.answerStream(
                userId,
                llmQuestion,
                excerpts,
                images,
                onChunk,
                generalChat,
                answerStyle
            );
            if (answer == null || answer.isBlank()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new LlmCompletion(
                answer,
                thirdPartyLlmClient.effectiveModelName(userId),
                null,
                null,
                null,
                thirdPartyLlmClient.hasActiveUserConfig(userId)
            ));
        }
        return onChunk == null
            ? answerLongDocumentPart(userId, llmQuestion, excerpts, images, generalChat, answerStyle, plan, continuation)
            : answerLongDocumentPartStream(userId, llmQuestion, excerpts, images, generalChat, answerStyle, plan, continuation, onChunk);
    }

    /**
     * 非流式长文档单段生成。
     * 只生成当前段并保存到历史，下一段由用户输入"继续"后再生成，避免单次请求过长导致页面报错。
     */
    private java.util.Optional<LlmCompletion> answerLongDocumentPart(
        long userId,
        String llmQuestion,
        List<String> excerpts,
        List<LlmImage> images,
        boolean generalChat,
        String answerStyle,
        LongDocumentPlan plan,
        LongDocumentContinuation continuation
    ) {
        int part = currentLongDocumentPart(plan, continuation);
        if (isLongDocumentComplete(plan, continuation)) {
            return java.util.Optional.of(new LlmCompletion(
                longDocumentCompleteMessage(plan),
                thirdPartyLlmClient.effectiveModelName(userId),
                null,
                null,
                null,
                thirdPartyLlmClient.hasActiveUserConfig(userId)
            ));
        }
        String partQuestion = buildLongDocumentPartQuestion(
            llmQuestion,
            continuation == null ? "" : continuation.generatedAnswer(),
            plan,
            part
        );
        java.util.Optional<LlmCompletion> completion = answerWithThirdParty(
            userId,
            partQuestion,
            excerpts,
            images,
            generalChat,
            answerStyle
        );
        if (completion.isEmpty() || completion.orElseThrow().content().isBlank()) {
            return java.util.Optional.empty();
        }
        LlmCompletion current = completion.orElseThrow();
        String answer = appendLongDocumentContinueNotice(current.content(), plan, part);
        return java.util.Optional.of(new LlmCompletion(
            answer,
            current.modelName(),
            current.promptTokens(),
            current.completionTokens(),
            current.totalTokens(),
            current.customModel()
        ));
    }

    /**
     * 流式长文档单段生成。
     * 模型输出当前段后，后端追加"继续"提示并一并推送给前端。
     */
    private java.util.Optional<LlmCompletion> answerLongDocumentPartStream(
        long userId,
        String llmQuestion,
        List<String> excerpts,
        List<LlmImage> images,
        boolean generalChat,
        String answerStyle,
        LongDocumentPlan plan,
        LongDocumentContinuation continuation,
        java.util.function.Consumer<String> onChunk
    ) {
        int part = currentLongDocumentPart(plan, continuation);
        if (isLongDocumentComplete(plan, continuation)) {
            String message = longDocumentCompleteMessage(plan);
            onChunk.accept(message);
            return java.util.Optional.of(new LlmCompletion(
                message,
                thirdPartyLlmClient.effectiveModelName(userId),
                null,
                null,
                null,
                thirdPartyLlmClient.hasActiveUserConfig(userId)
            ));
        }
        String partQuestion = buildLongDocumentPartQuestion(
            llmQuestion,
            continuation == null ? "" : continuation.generatedAnswer(),
            plan,
            part
        );
        String current = thirdPartyLlmClient.answerStream(
            userId,
            partQuestion,
            excerpts,
            images,
            onChunk,
            generalChat,
            answerStyle
        );
        if (current == null || current.isBlank()) {
            return java.util.Optional.empty();
        }
        String notice = longDocumentContinueNotice(plan, part);
        if (!notice.isBlank()) {
            onChunk.accept(notice);
        }
        return java.util.Optional.of(new LlmCompletion(
            current.trim() + notice,
            thirdPartyLlmClient.effectiveModelName(userId),
            null,
            null,
            null,
            thirdPartyLlmClient.hasActiveUserConfig(userId)
        ));
    }

    /**
     * 构造某一段的续写指令。
     * 对模型明确约束"只输出当前段"，避免每段都重新生成标题页、摘要或结束语。
     */
    private String buildLongDocumentPartQuestion(String llmQuestion, String generatedAnswer, LongDocumentPlan plan, int part) {
        String previous = abbreviateTail(generatedAnswer, 2_400);
        return """
            %s

            【超长文档分段生成指令】
            用户目标总长度约为 %d 字，系统计划分为 %d 段，这是第 %d 段。
            本段目标长度约为 %d 字。
            请只输出第 %d 段正文，不要解释分段机制，不要说"受限无法完成"。
            第 1 段需要自然开篇；中间段直接承接；最后 1 段需要自然收束。
            如果已生成内容尾部不为空，必须续写而不是重复前文。

            【已生成内容尾部】
            %s
            """.formatted(
            llmQuestion,
            plan.targetChars(),
            plan.parts(),
            part,
            plan.partTargetChars(),
            part,
            previous.isBlank() ? "无" : previous
        );
    }

    /** 在非流式回答末尾追加长文续写提示。 */
    private String appendLongDocumentContinueNotice(String answer, LongDocumentPlan plan, int part) {
        String normalized = answer == null ? "" : answer.trim();
        String notice = longDocumentContinueNotice(plan, part);
        return notice.isBlank() ? normalized : normalized + notice;
    }

    /** 构造长文续写提示；前端直接展示，用户按提示输入"继续"即可接着生成。 */
    private String longDocumentContinueNotice(LongDocumentPlan plan, int part) {
        if (part >= plan.parts()) {
            return "\n\n[超长文档已生成到计划末段。]";
        }
        return "\n\n[本段已完成。输入“继续”生成第 " + (part + 1) + "/" + plan.parts() + " 部分。]";
    }

    /** 用户继续请求已经超过计划段数时，直接返回明确提示，避免模型无边界续写。 */
    private String longDocumentCompleteMessage(LongDocumentPlan plan) {
        return "这篇超长文档已经生成到计划末段（共 " + plan.parts() + " 部分）。如需扩写，请直接说明新的补充要求。";
    }

    /** 计算当前应该生成第几段。 */
    private int currentLongDocumentPart(LongDocumentPlan plan, LongDocumentContinuation continuation) {
        int part = continuation == null ? 1 : continuation.nextPart();
        return Math.max(1, Math.min(part, plan.parts()));
    }

    /** 判断当前长文是否已经按计划生成完。 */
    private boolean isLongDocumentComplete(LongDocumentPlan plan, LongDocumentContinuation continuation) {
        return continuation != null && continuation.nextPart() > plan.parts();
    }

    /**
     * 根据用户原始问题判断是否需要长文分段。
     * <p>
     * 有明确字数时，需要同时具备文档生成意图才分段；未写字数时，只有明确说"超长/长篇/万字"才启用默认长文目标。
     * 这样可以避免"给我一个方案"这类普通问答被误拆成多段长文。
     */
    private LongDocumentPlan longDocumentPlan(String question) {
        String value = question == null ? "" : question.trim();
        int requestedChars = requestedCharCount(value);
        boolean documentIntent = hasDocumentGenerationIntent(value);
        if (requestedChars <= 0 && hasExplicitLongDocumentIntent(value)) {
            requestedChars = DEFAULT_LONG_DOCUMENT_TARGET_CHARS;
            documentIntent = true;
        }
        if (!documentIntent || requestedChars <= LONG_DOCUMENT_PART_TARGET_CHARS) {
            return new LongDocumentPlan(0, 1, LONG_DOCUMENT_PART_TARGET_CHARS);
        }
        int parts = Math.max(2, (int) Math.ceil((double) requestedChars / LONG_DOCUMENT_PART_TARGET_CHARS));
        int partTargetChars = Math.max(1_800, (int) Math.ceil((double) requestedChars / parts));
        return new LongDocumentPlan(requestedChars, parts, partTargetChars);
    }

    /** 提取用户显式要求的字数；未写字数时返回 0。 */
    private int requestedCharCount(String question) {
        Matcher matcher = LONG_DOCUMENT_CHAR_COUNT_PATTERN.matcher(question == null ? "" : question);
        int max = 0;
        while (matcher.find()) {
            double number = Double.parseDouble(matcher.group(1));
            int chars = (int) Math.round(number * ("万".equals(matcher.group(2)) ? 10_000 : 1));
            max = Math.max(max, chars);
        }
        return max;
    }

    /** 判断问题是否是文档类生成，而不是普通解释、总结或短回答。 */
    private boolean hasDocumentGenerationIntent(String question) {
        String value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return value.contains("文档")
            || value.contains("报告")
            || value.contains("论文")
            || value.contains("教程")
            || value.contains("讲稿")
            || value.contains("文章")
            || value.contains("作文")
            || value.contains("材料")
            || value.contains("写一篇")
            || value.contains("写一份")
            || value.contains("撰写")
            || value.contains("生成一篇")
            || value.contains("生成一份")
            || value.contains("write")
            || value.contains("document")
            || value.contains("report")
            || value.contains("essay");
    }

    /** 未写明确字数时，只对明显的超长表达启用默认长文分段。 */
    private boolean hasExplicitLongDocumentIntent(String question) {
        String value = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return hasDocumentGenerationIntent(value)
            && (value.contains("超长")
            || value.contains("长文")
            || value.contains("长篇")
            || value.contains("万字")
            || value.contains("完整长文")
            || value.contains("long-form")
            || value.contains("very long"));
    }

    /** 只保留已生成内容的尾部，给下一段提供衔接信息，同时控制上下文长度。 */
    private String abbreviateTail(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(normalized.length() - maxLength);
    }

    // ========== 流式问答接口 ==========

    /**
     * RAG 流式问答——核心入口方法（SSE/流式输出版本）。
     * <p>
     * 与 {@link #chat} 流程基本一致，但 LLM 的回答会以流式方式逐段推送给前端，
     * 提供更好的用户交互体验（边生成边显示）。
     * <p>
     * 额外处理：
     * <ul>
     *   <li>如果没有检索到片段且有资料ID，会构造一个提示让 LLM 基于资料标题回答</li>
     *   <li>使用 trackedChunk 包装回调，跟踪是否实际推送了流式数据</li>
     *   <li>如果流式推送没有实际数据（例如 LLM 返回了完整文本），会手动拆分成小块推送</li>
     * </ul>
     *
     * @param userId  用户 ID
     * @param request 问答请求
     * @param onChunk 流式回调，每收到一段 LLM 生成的文本就会调用此回调推送给前端
     * @return 流式问答结果，包含问答ID、最终回答、来源引用
     */
    @Transactional
    public RagStreamResult chatStream(long userId, ChatRequest request, java.util.function.Consumer<String> onChunk) {
        ensureChatUsageAvailable(userId);
        boolean generalChat = isGeneralChat(request);
        Long conversationId = resolveConversationId(userId, request.conversationId());
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

        LongDocumentContinuation continuation = resolveLongDocumentContinuation(userId, conversationId, request.question());
        String effectiveQuestion = continuation == null ? request.question() : continuation.originalQuestion();
        String questionWithContext = buildQuestionWithHistory(userId, conversationId, request.question(), request.history(), generalChat);
        String llmQuestion = thirdPartyLlmClient.isModelIdentityQuestion(request.question())
            ? request.question()
            : continuation == null ? questionWithContext : continuationQuestionWithHistory(questionWithContext, continuation);

        boolean customModel = thirdPartyLlmClient.hasActiveUserConfig(userId);
        String modelName = thirdPartyLlmClient.effectiveModelName(userId);
        AtomicBoolean streamedAnyChunk = new AtomicBoolean(false);
        java.util.function.Consumer<String> trackedChunk = delta -> {
            if (delta != null && !delta.isEmpty()) {
                streamedAnyChunk.set(true);
                onChunk.accept(delta);
            }
        };
        String answerStyle = isHomeworkStyle(request.answerStyle()) ? request.answerStyle() : "STUDY";
        LlmCompletion rawCompletion = answerWithLongDocumentSupport(
            userId,
            effectiveQuestion,
            llmQuestion,
            excerpts,
            images,
            generalChat,
            answerStyle,
            continuation,
            trackedChunk
        ).orElse(null);
        String answer = rawCompletion == null ? "" : rawCompletion.content();
        if (rawCompletion != null) {
            modelName = rawCompletion.modelName();
            customModel = rawCompletion.customModel();
        }
        if (answer.isBlank()) {
            // LLM 流式调用失败时回退到本地生成，保证 SSE 至少能返回完整答案。
            answer = generalChat
                ? buildGeneralFallbackAnswer(request.question())
                : buildCleanAnswer(request.question(), selectedChunks, request.answerStyle());
            modelName = "local-rag-demo";
            customModel = false;
        }
        if (!streamedAnyChunk.get() && !answer.isBlank()) {
            // 部分兼容接口会一次性返回全文，这里补发小块以维持前端流式体验。
            streamAnswerInSmallChunks(answer, onChunk);
        }

        boolean longDocumentAnswer = longDocumentPlan(effectiveQuestion).parts() > 1;
        String decoratedAnswer = generalChat
            ? decorateGeneralAnswer(answer)
            : longDocumentAnswer ? decorateLongDocumentAnswer(answer) : decorateAnswer(request, answer, selectedChunks);

        TokenUsage usage = estimateUsage(llmQuestion, answer);
        RagQuestionEntity savedQuestion = saveStreamResult(userId, request, conversationId, decoratedAnswer, selectedChunks, modelName, customModel, usage);
        updateConversationMemory(userId, savedQuestion.getConversationId());
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

    /**
     * 流式处理模型身份查询问题。
     * 直接将模型身份信息通过回调推送给前端。
     */
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
            resolveConversationId(userId, request.conversationId()),
            completion.content(),
            List.of(),
            completion.modelName(),
            completion.customModel(),
            usage
        );
        updateConversationMemory(userId, savedQuestion.getConversationId());
        recordUsageLog(userId, "RAG_CHAT_STREAM", savedQuestion.getId(), request, completion);
        return new RagStreamResult(savedQuestion.getId(), savedQuestion.getConversationId(), completion.content(), List.of());
    }

    /**
     * 将完整回答文本手动拆分成小块逐段推送，模拟流式输出效果。
     * 当 LLM 客户端无法真正流式推送时（例如返回了完整文本），使用此方法。
     * 每块之间间隔 25ms 以模拟实时生成的视觉效果。
     *
     * @param answer  完整的回答文本
     * @param onChunk 流式回调
     */
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

    // ========== 使用次数限制 ==========

    /**
     * 查询用户今日的聊天使用情况。
     * 管理员和配置了自定义模型的用户不受次数限制。
     *
     * @param userId 用户 ID
     * @return 使用情况响应，包含每日上限、已用次数、剩余次数
     */
    @Transactional(readOnly = true)
    public RagUsageResponse usage(long userId) {
        boolean unlimited = isAdminUser(userId) || thirdPartyLlmClient.hasActiveUserConfig(userId);
        long usedToday = countUserQuestionsToday(userId);
        Long remainingToday = unlimited ? null : Math.max(0, USER_DAILY_CHAT_LIMIT - usedToday);
        return new RagUsageResponse(USER_DAILY_CHAT_LIMIT, usedToday, remainingToday, unlimited);
    }

    /**
     * 检查用户的聊天使用次数是否还有剩余。
     * 管理员和配置了自定义模型的用户不限制。
     * 超限时抛出 429 错误。
     */
    private void ensureChatUsageAvailable(long userId) {
        if (isAdminUser(userId) || thirdPartyLlmClient.hasActiveUserConfig(userId)) {
            return;
        }
        long usedToday = countUserQuestionsToday(userId);
        if (usedToday >= USER_DAILY_CHAT_LIMIT) {
            throw new BusinessException(429, "今日问答次数已用完，请明天再试");
        }
    }

    /** 统计用户今日已提问的次数 */
    private long countUserQuestionsToday(long userId) {
        return ragQuestionRepository.countByUserIdAndCreatedAtGreaterThanEqual(userId, LocalDateTime.now().toLocalDate().atStartOfDay());
    }

    /** 判断用户是否为管理员角色 */
    private boolean isAdminUser(long userId) {
        return userRepository.findById(userId)
            .map(user -> user.getRole() == UserRole.ADMIN)
            .orElse(false);
    }

    // ========== 图片处理（多模态支持） ==========

    /**
     * 从用户请求中提取并验证上传的图片。
     * 限制：最多 8 张图片，每张不超过约 2MB，仅支持 JPG/PNG/WEBP/GIF 格式。
     *
     * @param request 问答请求（可能包含图片数据）
     * @return 验证通过的图片列表
     * @throws BusinessException 图片数量超限或格式不支持时抛出
     */
    private List<LlmImage> userImages(ChatRequest request) {
        if (request.images() == null || request.images().isEmpty()) {
            return List.of();
        }
        if (request.images().size() > MAX_USER_IMAGES_PER_REQUEST) {
            throw new BusinessException(400, "一次最多上传 8 张图片");
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

    /** 标准化图片媒体类型，仅允许 image/jpeg、image/png、image/webp、image/gif */
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

    /** 标准化图片 Base64 数据：去除 data URL 前缀，验证格式，去除空白字符 */
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

    /**
     * 将用户问题附带的图片规范化为可持久化 JSON。
     *
     * <p>前端可能传 dataUrl，也可能传 base64Data + mediaType。这里统一转成
     * {@code data:<mediaType>;base64,...} 形式，并限制数量、类型和大小。
     * 这些图片已经在当前请求中传给 LLM；写入数据库主要用于历史消息回显。</p>
     */
    private String questionImagesJson(ChatRequest request) {
        if (request.images() == null || request.images().isEmpty()) {
            return null;
        }
        List<ChatImage> normalizedImages = request.images().stream()
            .filter(image -> image != null)
            .map(image -> {
                String mediaType = normalizeImageMediaType(image.resolvedMediaType());
                String base64Data = normalizeImageBase64(image);
                if (base64Data.isBlank()) {
                    return null;
                }
                if (base64Data.length() > MAX_USER_IMAGE_BASE64_CHARS) {
                    throw new BusinessException(413, "图片过大，请压缩后再上传");
                }
                return new ChatImage("data:" + mediaType + ";base64," + base64Data, null, mediaType);
            })
            .filter(image -> image != null)
            .limit(MAX_USER_IMAGES_PER_REQUEST)
            .toList();
        return normalizedImages.isEmpty() ? null : writeJson(normalizedImages);
    }

    /**
     * 读取历史问题里的图片附件。
     *
     * <p>历史回显不是核心问答链路，旧数据或脏数据解析失败时返回空列表，
     * 避免一条损坏的附件 JSON 影响整段会话打开。</p>
     */
    private List<ChatImage> readQuestionImages(RagQuestionEntity question) {
        String json = question.getQuestionImagesJson();
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return OBJECT_MAPPER.readerForListOf(ChatImage.class).readValue(json);
        } catch (Exception exception) {
            return List.of();
        }
    }

    /**
     * 将临时资料附件写入问答历史。
     *
     * <p>只有已抽取到正文的临时资料才会保存。空文本附件对 LLM 没有上下文价值，
     * 也不应该在历史消息里显示成可预览资料。</p>
     */
    private String questionTemporaryMaterialJson(ChatRequest request) {
        ChatTemporaryMaterial material = request.temporaryMaterial();
        if (material == null) {
            return null;
        }
        String text = material.text() == null ? "" : material.text().trim();
        if (text.isBlank()) {
            return null;
        }
        return writeJson(new ChatTemporaryMaterial(
            material.id(),
            material.title(),
            material.originalName(),
            material.sourceType(),
            text,
            material.excerpt(),
            material.fileSize()
        ));
    }

    /**
     * 读取历史问题里的临时资料附件。
     *
     * <p>和图片附件一样，历史回显容错优先：解析失败返回 null，让消息正文仍可正常展示。</p>
     */
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

    // ========== 检索阶段：上下文片段选择（RAG 核心） ==========

    /**
     * 上下文片段选择——检索阶段的核心调度方法。
     * <p>
     * 根据问题类型和用户当前阅读位置，采用不同的检索策略：
     * <ol>
     *   <li><b>通用聊天/选中文本</b>：不检索资料片段，返回空列表</li>
     *   <li><b>关键词检索优先</b>：先尝试精确关键词匹配（如"什么是XXX"）</li>
     *   <li><b>当前页片段</b>：如果用户正在阅读某页，优先使用该页的片段</li>
     *   <li><b>局部上下文问题</b>：对"这页讲什么"类问题，只用当前页片段</li>
     *   <li><b>向量+BM25 混合检索</b>：对一般性问题，执行多路检索 + 融合 + 重排</li>
     *   <li><b>资料概览</b>：对"这是什么书"类问题，返回资料前几个片段</li>
     * </ol>
     *
     * @param userId      用户 ID
     * @param request     问答请求
     * @param generalChat 是否为通用聊天模式
     * @return 排序后的相关片段列表（ScoredChunk 包含片段和相关性分数）
     */
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

    /**
     * 多路混合检索 + 融合 + 重排——检索的核心算法。
     * <p>
     * 检索策略：
     * <ol>
     *   <li><b>查询扩展</b>：将原始问题扩展为多个变体查询，提高召回率</li>
     *   <li><b>HyDE 检索</b>：让 LLM 先生成一个假设性回答，用它做向量检索</li>
     *   <li><b>向量语义检索</b>：将问题转为向量，计算与片段向量的余弦相似度</li>
     *   <li><b>BM25 关键词检索</b>：基于倒排索引的传统关键词匹配</li>
     *   <li><b>摘要种子检索</b>：如果资料摘要覆盖了问题关键词，补充前几个片段</li>
     *   <li><b>混合融合</b>：将三路结果按权重融合（向量 0.48 + BM25 0.30 + 摘要 0.12 + 关键词覆盖 0.10）</li>
     *   <li><b>Reranker 重排</b>：使用 Cross-Encoder 模型对融合结果精排</li>
     * </ol>
     * <p>
     * 结果会缓存，短时间内相同查询直接返回缓存结果。
     *
     * @param userId     用户 ID
     * @param question   检索用的问题文本（可能已经过改写）
     * @param materialId 资料 ID（为 null 时搜索用户所有资料）
     * @return 经过融合和重排的相关片段列表
     */
    private List<ScoredChunk> selectVectorOrKeywordChunks(long userId, String question, Long materialId) {
        List<LearningMaterialEntity> materials = retrievalScopeMaterials(userId, materialId);
        String cacheKey = retrievalCacheKey(userId, materialId, question, materials);
        List<ScoredChunk> cachedChunks = cachedRetrievalChunks(cacheKey, userId);
        if (cachedChunks != null) {
            // 相同资料版本和查询命中缓存时，跳过向量、BM25 和 rerank 的重复计算。
            return cachedChunks;
        }

        List<ScoredChunk> vectorChunks = new ArrayList<>();
        List<ScoredChunk> bm25Chunks = new ArrayList<>();
        // 摘要种子检索：如果资料摘要中包含了问题关键词，补充该资料的前几个片段
        List<ScoredChunk> summarySeedChunks = findSummarySeedChunks(userId, question, materialId);
        // 查询扩展：将原始问题扩展为多个变体（如添加"定义 原理 作用"等补充词）
        List<String> queries = expandedRetrievalQueries(question);
        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            // 主查询权重为 1.0，扩展查询权重递减为 0.82
            double weight = i == 0 ? 1.0 : 0.82;
            // 改写查询降权参与召回，避免扩展词压过用户原始问题。
            // 向量语义检索：计算问题向量与片段向量的余弦相似度
            vectorChunks.addAll(weightedChunks(findVectorScoredChunks(userId, query, materialId), weight));
            // BM25 关键词检索：基于倒排索引的词频统计匹配
            bm25Chunks.addAll(weightedChunks(findScoredChunks(userId, query, materialId), weight));
        }
        // HyDE（假设性文档嵌入）检索：让 LLM 生成假设性回答，用它做向量检索
        // 原理：假设性回答的向量通常比问题的向量更接近真实答案所在片段的向量
        hydeRetrievalQuery(question).ifPresent(hydeQuery ->
            // HyDE 只作为语义召回补充，不参与 BM25，避免假设答案污染精确关键词匹配。
            vectorChunks.addAll(weightedChunks(
                findVectorScoredChunks(userId, hydeQuery, materialId),
                queryExpansionProperties.hydeWeight()
            ))
        );
        // 混合融合：将向量/BM25/摘要三路结果按权重融合，再通过 Reranker 精排
        List<ScoredChunk> selected = selectTopChunks(fuseAndRerankChunks(question, vectorChunks, bm25Chunks, summarySeedChunks));
        // 缓存检索结果
        rememberRetrievalResult(cacheKey, selected);
        return selected;
    }

    /**
     * 摘要种子检索：如果资料的自动摘要文本覆盖了问题关键词，
     * 则将该资料的前几个片段作为"种子"补充到检索结果中。
     * <p>
     * 原理：如果摘要中提到了问题相关的关键词，说明该资料整体与问题相关，
     * 其前面的片段很可能包含背景信息或总述，对回答有辅助价值。
     */
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

    /**
     * 查询扩展：将原始问题扩展为多个检索查询变体，提高检索召回率。
     * <p>
     * 扩展策略（当配置开启时）：
     * <ul>
     *   <li>调用 LLM 生成语义等价的改写查询</li>
     *   <li>如果 LLM 不可用，使用本地规则扩展（如添加"定义 原理 作用"等）</li>
     *   <li>去重后最多返回 maxQueries 个查询</li>
     * </ul>
     *
     * @param question 原始问题文本
     * @return 扩展后的查询列表（第一个始终是原始问题）
     */
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

    /**
     * HyDE（Hypothetical Document Embedding，假设性文档嵌入）检索查询生成。
     * <p>
     * 原理：先让 LLM 根据问题"假装"生成一个回答（假设性文档），
     * 然后用这个假设性文档的向量去检索——因为假设性文档的语义通常比问题本身
     * 更接近真正答案所在片段的语义。
     * <p>
     * 例如：问题"什么是快排" -> 假设性文档"快速排序是一种分治排序算法..." -> 用此向量检索
     */
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
                // 片段被删除后立即丢弃缓存，避免返回悬空来源。
                retrievalResultCache.remove(cacheKey);
                return null;
            }
            LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(chunk.getMaterialId(), userId).orElse(null);
            if (material == null || material.getParseStatus() != MaterialParseStatus.SUCCESS) {
                // 资料归属或解析状态变化时，缓存结果不再可信。
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
            // 简单全量清空比维护 LRU 更轻量，缓存只用于短期加速。
            retrievalResultCache.clear();
        }
        retrievalResultCache.put(cacheKey, new RetrievalCacheEntry(
            chunks.stream()
                .map(chunk -> new CachedScoredChunk(chunk.chunk().getId(), chunk.score(), List.copyOf(chunk.highlightTerms())))
                .toList()
        ));
    }

    // ========== 检索结果融合与重排 ==========

    /**
     * 混合融合 + 重排——将多路检索结果合并为统一排序。
     * <p>
     * 融合算法：
     * <ol>
     *   <li>将同一片段在各路检索中的分数合并到一个 HybridCandidate 中</li>
     *   <li>对各路分数做归一化（除以该路最高分）</li>
     *   <li>按权重加权求和：
     *       <ul>
     *         <li>向量语义分数：48%（捕捉语义相似性）</li>
     *         <li>BM25 关键词分数：30%（捕捉精确关键词匹配）</li>
     *         <li>摘要种子分数：12%（辅助整体相关性判断）</li>
     *         <li>关键词覆盖率：10%（问题关键术语在片段中的覆盖比例）</li>
     *       </ul>
     *   </li>
     *   <li>调用 Reranker（Cross-Encoder）对融合结果做精排序</li>
     * </ol>
     *
     * @param question           原始问题
     * @param vectorChunks       向量语义检索结果
     * @param bm25Chunks         BM25 关键词检索结果
     * @param summarySeedChunks  摘要种子检索结果
     * @return 融合并重排后的片段列表
     */
    private List<ScoredChunk> fuseAndRerankChunks(
        String question,
        List<ScoredChunk> vectorChunks,
        List<ScoredChunk> bm25Chunks,
        List<ScoredChunk> summarySeedChunks
    ) {
        // 第一步：收集各路检索结果到统一的候选池中（按 chunk ID 去重合并）
        Map<Long, HybridCandidate> candidates = new LinkedHashMap<>();
        for (ScoredChunk chunk : vectorChunks) {
            HybridCandidate candidate = candidates.computeIfAbsent(chunk.chunk().getId(), ignored -> new HybridCandidate(chunk));
            candidate.vectorScore = Math.max(candidate.vectorScore, chunk.score()); // 取最高向量分数
            candidate.highlightTerms = chunk.highlightTerms();
        }
        for (ScoredChunk chunk : bm25Chunks) {
            HybridCandidate candidate = candidates.computeIfAbsent(chunk.chunk().getId(), ignored -> new HybridCandidate(chunk));
            // 同一片段可能被多个扩展查询命中，只保留该路最高分。
            candidate.bm25Score = Math.max(candidate.bm25Score, chunk.score());
            if (candidate.highlightTerms.isEmpty()) {
                candidate.highlightTerms = chunk.highlightTerms();
            }
        }
        for (ScoredChunk chunk : summarySeedChunks) {
            HybridCandidate candidate = candidates.computeIfAbsent(chunk.chunk().getId(), ignored -> new HybridCandidate(chunk));
            // 摘要种子用于把整篇相关的资料前几个片段拉入候选池。
            candidate.summaryScore = Math.max(candidate.summaryScore, chunk.score());
            if (candidate.highlightTerms.isEmpty()) {
                candidate.highlightTerms = chunk.highlightTerms();
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 第二步：各路分数归一化（除以该路最高分，缩放到 0-1 范围）
        double maxVector = candidates.values().stream().mapToDouble(candidate -> candidate.vectorScore).max().orElse(0.0);
        double maxBm25 = candidates.values().stream().mapToDouble(candidate -> candidate.bm25Score).max().orElse(0.0);
        double maxSummary = candidates.values().stream().mapToDouble(candidate -> candidate.summaryScore).max().orElse(0.0);
        List<String> queryTerms = significantQueryTerms(question);

        // 第三步：加权融合各路分数
        List<ScoredChunk> fusedChunks = candidates.values().stream()
            .map(candidate -> {
                double vectorScore = maxVector <= 0.0 ? 0.0 : candidate.vectorScore / maxVector;
                double bm25Score = maxBm25 <= 0.0 ? 0.0 : candidate.bm25Score / maxBm25;
                double summaryScore = maxSummary <= 0.0 ? 0.0 : candidate.summaryScore / maxSummary;
                // 关键词覆盖率：问题中的关键术语在片段文本中的命中比例
                double termScore = queryTermCoverage(retrievalText(candidate.chunk), queryTerms);
                // 加权公式：向量48% + BM25的30% + 摘要12% + 关键词覆盖率10%
                double fusedScore = (0.48 * vectorScore) + (0.30 * bm25Score) + (0.12 * summaryScore) + (0.10 * termScore);
                return new ScoredChunk(candidate.material, candidate.chunk, fusedScore, candidate.highlightTerms);
            })
            .filter(chunk -> chunk.score() > 0.0)
            .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
            .toList();
        // 第四步：调用 Reranker（Cross-Encoder）对融合结果做精排序
        return rerankChunks(question, fusedChunks);
    }

    /**
     * Reranker 精排：使用 Cross-Encoder 模型对候选片段重新排序。
     * <p>
     * 与向量检索的双塔模型（分别编码问题和片段）不同，
     * Cross-Encoder 会将问题和片段拼接在一起输入模型，
     * 因此能捕捉更细致的语义关系，但计算成本更高，只用于精排少量候选。
     *
     * @param question 原始问题
     * @param chunks   融合后的候选片段列表
     * @return 重排后的片段列表（Reranker 分数优先，未被重排的片段排在最后）
     */
    private List<ScoredChunk> rerankChunks(String question, List<ScoredChunk> chunks) {
        if (chunks.size() < 2) {
            return chunks;
        }
        List<RerankCandidate> candidates = chunks.stream()
            .map(chunk -> new RerankCandidate(chunk.chunk().getId(), retrievalText(chunk.chunk()), chunk.score()))
            .toList();
        List<RerankedCandidate> rerankedCandidates = rerankerClient.rerank(question, candidates);
        if (rerankedCandidates.isEmpty()) {
            // 外部重排不可用或无有效结果时，保留融合分数排序。
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
                // Reranker 未覆盖的候选保留在末尾，避免召回结果被意外丢弃。
                reranked.add(chunk);
            }
        }
        return reranked;
    }

    // ========== 查询理解：问题意图分析与关键词提取 ==========

    /**
     * 计算问题关键词在文本中的覆盖率。
     * 用于融合阶段的关键词覆盖率评分。
     *
     * @param text       待检查的文本
     * @param queryTerms 问题中提取的关键词列表
     * @return 覆盖率（0.0-1.0），1.0 表示所有关键词都出现了
     */
    private double queryTermCoverage(String text, List<String> queryTerms) {
        if (queryTerms.isEmpty()) {
            return 0.0;
        }
        String normalizedText = normalizeForTermMatch(text);
        long matches = queryTerms.stream().filter(normalizedText::contains).count();
        return (double) matches / queryTerms.size();
    }

    /**
     * 从问题中提取有意义的关键词（用于检索和覆盖率计算）。
     * <p>
     * 提取策略：
     * <ol>
     *   <li>先尝试用意图解析器提取（更精确）</li>
     *   <li>如果没有匹配到意图，用正则提取中文和英文的 2+ 字符词</li>
     *   <li>过滤掉弱查询词（如"什么"、"怎么"、"为什么"等停用词）</li>
     *   <li>最多返回 6 个关键词</li>
     * </ol>
     */
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

    /**
     * 判断是否为"弱查询词"——即没有实际检索意义的高频词（停用词）。
     * 如"什么"、"怎么"、"为什么"等疑问词，"this"、"that"等代词。
     */
    private boolean isWeakQueryTerm(String term) {
        return Set.of(
            "什么", "怎么", "为什么", "哪里", "哪个", "哪些", "一下", "介绍", "解释", "区别",
            "what", "how", "why", "where", "which", "does", "this", "that", "with"
        ).contains(term);
    }

    // ========== 关键词精确检索 ==========

    /**
     * 从问题中提取定义类术语并检索（如"什么是快排" -> 检索"快排"）。
     */
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

    /**
     * 关键词检索：在指定资料的片段中精确匹配关键词。
     * <p>
     * 对每个片段的检索文本（包含正文、层级路径、章节标题、摘要、关键词），
     * 检查是否包含查询术语，然后按关键词匹配分数排序。
     *
     * @param userId     用户 ID
     * @param query      解析后的关键词查询（包含术语列表和意图类型）
     * @param materialId 必须指定资料 ID
     * @return 按匹配分数降序排列的片段列表（最多 5 个）
     */
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

    /**
     * 从问题中提取关键词查询——意图识别的核心方法。
     * <p>
     * 按优先级依次尝试匹配以下意图类型：
     * <ol>
     *   <li><b>定义类</b>：什么是 XXX / XXX 的定义 / define XXX</li>
     *   <li><b>对比类</b>：A 和 B 的区别 / A vs B</li>
     *   <li><b>出现位置类</b>：XXX 在哪里提到 / where is XXX mentioned</li>
     *   <li><b>功能作用类</b>：XXX 有什么用 / what is XXX used for</li>
     *   <li><b>方面特征类</b>：XXX 的优缺点 / advantages of XXX</li>
     *   <li><b>开放式话题类</b>：介绍一下 XXX / how does XXX work</li>
     *   <li><b>定义术语提取</b>：作为兜底，提取定义类问题中的术语</li>
     * </ol>
     *
     * @param question 问题文本
     * @return 解析后的关键词查询（含术语和意图），无法解析时返回 null
     */
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

    /**
     * 提取中文定义类查询：如"什么是快排" -> 术语"快排"，意图 DEFINITION。
     * 同时支持前缀模式（什么是 XXX）和后缀模式（XXX 的定义）。
     */
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

    /**
     * 提取对比类查询：如"A 和 B 有什么区别" -> 术语[A, B]，意图 COMPARISON。
     * 支持中文（A和B的区别、A和B哪个好）和英文（difference between A and B、A vs B）。
     */
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

    /**
     * 提取出现位置类查询：如"快排在哪里提到" -> 术语"快排"，意图 OCCURRENCE。
     * 用于查找某个概念在资料中哪些位置出现过。
     */
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

    /**
     * 提取功能作用类查询：如"索引有什么用" -> 术语"索引"，意图 FUNCTION。
     * 支持中文（XXX的作用、XXX用来做什么）和英文（what is XXX used for、role of XXX）。
     */
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

    /**
     * 提取方面特征类查询：如"TCP 的优缺点" -> 术语"TCP"，意图 FUNCTION。
     * 匹配优缺点、优点、缺点、特点、特征、优势、劣势、局限、风险等。
     */
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

    /**
     * 工厂方法：创建关键词查询对象，自动清理和去重术语。
     * 最多保留 2 个术语。
     */
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

    /**
     * 从定义类问题中提取术语名称。
     * 例如"什么是快速排序" -> "快速排序"。
     * 自动去除"一下"、"这个"、"资料里的"等前缀和"是什么"等后缀。
     */
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

    /**
     * 获取片段的完整检索文本：拼接正文、层级路径、章节标题、摘要、关键词。
     * 用于 BM25 检索和关键词匹配时的全字段搜索。
     */
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

    /**
     * 关键词匹配评分：根据关键词在文本中的位置和意图类型计算相关性分数。
     * <p>
     * 评分规则：
     * <ul>
     *   <li>每个命中的关键词：基础 20 分 + 位置奖励（出现越靠前分越高，最高 +5 分）</li>
     *   <li>DEFINITION 意图：如果文本包含"定义"、"概念"、"是指"等词，额外 +10 分</li>
     *   <li>FUNCTION 意图：如果文本包含"作用"、"用途"、"用于"等词，额外 +10 分</li>
     *   <li>COMPARISON 意图：如果所有术语都出现，额外 +12 分；含"区别"等词再 +10 分</li>
     *   <li>OCCURRENCE 意图：命中即 +4 分（因为只需要定位位置）</li>
     * </ul>
     *
     * @param text          片段文本
     * @param normalizedTerms 归一化后的关键词列表
     * @param intent        问题意图类型
     * @return 匹配分数（越高表示越相关）
     */
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

    /**
     * 文本归一化：转小写，去除所有标点符号和空白字符。
     * 用于关键词匹配时的标准化比较，避免因标点或空格导致匹配失败。
     */
    private String normalizeForTermMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？；：“”‘’（）《》、]+", "");
    }

    /**
     * SHA-256 哈希：用于生成检索缓存的唯一键。
     * 将问题文本哈希后作为缓存键的一部分，避免存储原始文本占用内存。
     */
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

    /**
     * 判断是否为"局部上下文问题"——用户在问当前阅读位置的相关内容。
     * 如"这页讲什么"、"当前内容"、"本章"等。
     */
    private boolean isLocalContextQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return LOCAL_CONTEXT_QUESTION_PATTERN.matcher(question).find();
    }

    /**
     * 判断是否为"资料概览问题"——用户在问整份资料的内容。
     * 如"这是什么书"、"介绍一下"、"概括"等。
     */
    private boolean isMaterialOverviewQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return MATERIAL_OVERVIEW_QUESTION_PATTERN.matcher(question).find();
    }

    /**
     * 获取资料概览片段：返回资料的前 5 个有效片段。
     * 用于回答"这是什么书"、"介绍一下这份资料"等概览类问题。
     * 前面的片段分数更高（0.95 递减到 0.75）。
     */
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

    /**
     * 获取与当前片段同页的所有片段。
     * 用于用户在阅读某一页时，自动加载该页的完整上下文。
     * 当前片段分数为 1.0，同页其他片段分数为 0.95。
     */
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

    /**
     * 根据请求中的页面信息查找当前页片段。
     * 优先使用 currentPageChunkIds（前端传来的具体片段ID列表），
     * 其次使用 currentPageNo（前端传来的页码）来定位当前页。
     */
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

    /**
     * 获取与当前片段同章节的所有片段。
     * 根据章节标题（sectionTitle）匹配，同一章节的片段具有内容上的连贯性。
     * 当前片段分数为 1.0，同章节其他片段分数为 0.9。
     */
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

    // ========== 上下文构建阶段：将片段组装为 LLM 可理解的文本 ==========

    /**
     * 构建发送给 LLM 的上下文摘录列表。
     * <p>
     * 如果用户选中了某段文本，直接以选中内容为上下文；
     * 如果用户正在阅读某个片段，当前片段标记为"优先依据"，其他为"补充依据"；
     * 否则直接使用检索到的片段作为上下文。
     */
    private List<String> buildExcerpts(ChatRequest request, List<ScoredChunk> selectedChunks) {
        if (request.selectedText() != null && !request.selectedText().isBlank()) {
            return List.of("[用户选中内容]\n原文：" + truncate(request.selectedText(), MAX_SELECTED_TEXT_CONTEXT_CHARS));
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

    /**
     * 保存流式问答的结果到数据库。
     *
     * <p>流式接口在前端已经实时展示了回答，但只有收到最终 done 事件后，
     * 才会把完整 answer、token 用量、来源引用、图片和临时资料附件一起落库。
     * 这样历史记录中看到的是最终稳定版本，而不是中间增量。</p>
     *
     * <p>保存失败时直接抛出业务异常。流式问答如果答完却没有落库，前端重进页面就无法恢复历史，
     * 因此这里不能再静默返回 0 号记录。</p>
     */
    private RagQuestionEntity saveStreamResult(
        long userId,
        ChatRequest request,
        Long conversationId,
        String answer,
        List<ScoredChunk> chunks,
        String modelName,
        boolean customModel,
        TokenUsage usage
    ) {
        try {
            RagQuestionEntity question = new RagQuestionEntity();
            question.setUserId(userId);
            question.setConversationId(conversationId);
            question.setMaterialId(materialQuestionId(request));
            question.setQuestionText(request.question());
            question.setQuestionImagesJson(questionImagesJson(request));
            question.setQuestionTemporaryMaterialJson(questionTemporaryMaterialJson(request));
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
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Failed to persist stream RAG result for user {}", userId, exception);
            throw new BusinessException(500, "流式问答保存失败，请重试");
        }
    }

    // ========== Token 使用量计算 ==========

    /**
     * 获取 LLM 完成结果的 Token 使用量。
     * 优先使用模型返回的实际 Token 数，如果模型没有返回则估算。
     */
    private TokenUsage completionUsage(LlmCompletion completion, String prompt, String answer) {
        if (completion.totalTokens() != null || completion.promptTokens() != null || completion.completionTokens() != null) {
            return new TokenUsage(completion.promptTokens(), completion.completionTokens(), completion.totalTokens());
        }
        return estimateUsage(prompt, answer);
    }

    /** 估算 Token 使用量：按文本长度 / 1.8 近似计算（中文平均约 1.8 字符 = 1 token） */
    private TokenUsage estimateUsage(String prompt, String answer) {
        int promptTokens = estimateTokens(prompt);
        int completionTokens = estimateTokens(answer);
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    private int estimateTokens(String text) {
        String value = text == null ? "" : text;
        return Math.max(1, (int) Math.ceil(value.length() / 1.8));
    }

    /**
     * 记录使用日志：将每次问答的模型信息、Token 消耗、操作模式等写入 usage_record 表。
     * 用于后续的用量统计和费用审计。
     */
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

    /** Token 使用量记录：promptTokens=输入Token数，completionTokens=输出Token数，totalTokens=总计 */
    private record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
    }

    /**
     * 根据 chunkId 查找指定的片段。
     * 用于用户在阅读某个具体片段时，加载该片段作为上下文。
     */
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

    // ========== 对话历史处理 ==========

    /**
     * 组装发给模型的多轮上下文。
     *
     * <p>上下文由“长期会话摘要 + 最近原文窗口 + 当前问题”组成。
     * 较早轮次进入摘要，最近轮次保留原文，从而兼顾更多轮记忆和请求体大小。</p>
     */
    private String buildQuestionWithHistory(
        long userId,
        Long conversationId,
        String question,
        List<ChatMessage> history,
        boolean generalChat
    ) {
        StringBuilder sb = new StringBuilder();
        String memorySummary = conversationMemorySummary(userId, conversationId);
        List<ChatMessage> recentHistory = compactRecentHistory(history, generalChat);
        if (memorySummary.isBlank() && recentHistory.isEmpty()) {
            return question;
        }
        if (!memorySummary.isBlank()) {
            sb.append("长期会话摘要：\n")
                .append(truncate(memorySummary, MAX_MEMORY_PROMPT_CHARS))
                .append("\n\n");
        }

        if (!recentHistory.isEmpty()) {
            sb.append("最近对话原文：\n");
            for (ChatMessage msg : recentHistory) {
                sb.append(normalizeRole(msg.role()))
                    .append("：")
                    .append(truncateHistoryContent(msg))
                    .append("\n");
            }
            sb.append("\n");
        }

        sb.append("当前问题：").append(question);
        return sb.toString();
    }

    /**
     * 识别"继续"类请求对应的长文续写状态。
     *
     * <p>续写状态优先从服务端历史恢复，而不是只依赖前端传来的 history。
     * 这样用户刷新页面后，只要仍在同一个会话里输入"继续"，后端仍能接上上一段。</p>
     */
    private LongDocumentContinuation resolveLongDocumentContinuation(long userId, Long conversationId, String question) {
        if (!isContinueRequest(question) || conversationId == null || conversationId <= 0) {
            return null;
        }
        List<RagQuestionEntity> questions = ragQuestionRepository
            .findByUserIdAndConversationIdOrderByCreatedAtAsc(userId, conversationId);
        if (questions.isEmpty()) {
            return null;
        }
        String originalQuestion = "";
        StringBuilder generatedAnswer = new StringBuilder();
        int completedParts = 0;
        for (RagQuestionEntity item : questions) {
            String itemQuestion = item.getQuestionText();
            LongDocumentPlan itemPlan = longDocumentPlan(itemQuestion);
            if (itemPlan.parts() > 1 && !isContinueRequest(itemQuestion)) {
                originalQuestion = itemQuestion;
                generatedAnswer.setLength(0);
                appendLongDocumentHistoryPart(generatedAnswer, item.getAnswerText());
                completedParts = 1;
                continue;
            }
            if (!originalQuestion.isBlank() && isContinueRequest(itemQuestion)) {
                appendLongDocumentHistoryPart(generatedAnswer, item.getAnswerText());
                completedParts++;
            }
        }
        if (originalQuestion.isBlank() || generatedAnswer.isEmpty()) {
            return null;
        }
        LongDocumentPlan plan = longDocumentPlan(originalQuestion);
        return new LongDocumentContinuation(
            originalQuestion,
            generatedAnswer.toString().trim(),
            completedParts,
            completedParts + 1
        );
    }

    /** 判断用户当前输入是否是在要求继续上一段长文。 */
    private boolean isContinueRequest(String question) {
        String value = question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
        String compact = value.replaceAll("[\\s\\p{Punct}\\u3000-\\u303f\\uff00-\\uffef]+", "");
        return compact.equals("继续")
            || compact.equals("继续写")
            || compact.equals("接着写")
            || compact.equals("续写")
            || compact.equals("下一段")
            || compact.equals("下一部分")
            || compact.equals("继续生成")
            || compact.equals("继续输出")
            || compact.equals("goon")
            || compact.equals("continue")
            || compact.equals("next");
    }

    /** 把历史中的一段长文回答追加到续写上下文，去掉系统追加的"继续"提示。 */
    private void appendLongDocumentHistoryPart(StringBuilder generatedAnswer, String answerText) {
        String cleaned = cleanLongDocumentAnswerForContinuation(answerText);
        if (cleaned.isBlank()) {
            return;
        }
        if (!generatedAnswer.isEmpty()) {
            generatedAnswer.append("\n\n");
        }
        generatedAnswer.append(cleaned);
    }

    /** 清理长文历史回答中的续写提示和引用装饰，避免下一段 prompt 重复这些系统文字。 */
    private String cleanLongDocumentAnswerForContinuation(String answer) {
        return cleanAnswerForMemory(answer)
            .replaceAll("\\[本段已完成。输入“继续”生成第 \\d+/\\d+ 部分。]", "")
            .replaceAll("\\[超长文档已生成到计划末段。]", "")
            .trim();
    }

    /**
     * 为"继续"请求补充原始长文需求和已生成尾部。
     *
     * <p>这段信息会再进入单段生成指令，双重约束模型只续写当前段，不重新开篇。</p>
     */
    private String continuationQuestionWithHistory(String questionWithContext, LongDocumentContinuation continuation) {
        return """
            %s

            【长文续写上下文】
            原始长文需求：
            %s

            已生成内容尾部：
            %s

            当前用户输入的是"继续"，请接着原始长文需求往下写，不要重复已经生成的内容。
            """.formatted(
            questionWithContext,
            continuation.originalQuestion(),
            abbreviateTail(continuation.generatedAnswer(), 2_400)
        );
    }

    /**
     * 读取持久化的长期摘要。
     *
     * <p>新会话第一轮还没有 conversationId，因此直接返回空摘要。</p>
     */
    private String conversationMemorySummary(long userId, Long conversationId) {
        if (conversationId == null || conversationId <= 0) {
            return "";
        }
        return ragConversationMemoryRepository.findByUserIdAndConversationId(userId, conversationId)
            .map(RagConversationMemoryEntity::getSummary)
            .orElse("");
    }

    /**
     * 从最近窗口中按预算挑选历史原文。
     *
     * <p>从近到远选择，优先保留最新讨论；滑出窗口的较早内容由长期摘要承担。</p>
     */
    private List<ChatMessage> compactRecentHistory(List<ChatMessage> history, boolean generalChat) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int maxItems = generalChat ? GENERAL_RECENT_HISTORY_ITEMS : MATERIAL_RECENT_HISTORY_ITEMS;
        int charBudget = generalChat ? GENERAL_HISTORY_CHAR_BUDGET : MATERIAL_HISTORY_CHAR_BUDGET;
        int start = Math.max(0, history.size() - maxItems);
        List<ChatMessage> window = history.subList(start, history.size());
        List<ChatMessage> selected = new ArrayList<>();
        int usedChars = 0;
        for (int i = window.size() - 1; i >= 0; i--) {
            ChatMessage message = window.get(i);
            String content = truncateHistoryContent(message);
            int nextChars = normalizeRole(message.role()).length() + content.length() + 2;
            if (!selected.isEmpty() && usedChars + nextChars > charBudget) {
                break;
            }
            selected.add(0, new ChatMessage(normalizeRole(message.role()), content));
            usedChars += nextChars;
        }
        return selected;
    }

    /** 按角色裁剪单条历史，避免超长回答拖慢后续所有请求。 */
    private String truncateHistoryContent(ChatMessage message) {
        int maxChars = "assistant".equalsIgnoreCase(message.role())
            ? MAX_HISTORY_ASSISTANT_CHARS
            : MAX_HISTORY_USER_CHARS;
        return truncate(message.content(), maxChars);
    }

    /** 统一角色名，防止前端或旧数据里的大小写差异影响 prompt。 */
    private String normalizeRole(String role) {
        return "assistant".equalsIgnoreCase(role) ? "assistant" : "user";
    }

    /** 按字符上限裁剪文本，并用明确标记告诉模型内容被截断。 */
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

    /**
     * 更新会话长期摘要。
     *
     * <p>只处理已经滑出最近原文窗口的问答；最近若干轮继续以原文形式进入 prompt。
     * 摘要由本地规则生成，不额外调用 LLM，避免因为维护记忆增加一次模型请求耗时。</p>
     */
    private void updateConversationMemory(long userId, Long conversationId) {
        if (conversationId == null || conversationId <= 0) {
            return;
        }
        List<RagQuestionEntity> questions = ragQuestionRepository
            .findByUserIdAndConversationIdOrderByCreatedAtAsc(userId, conversationId);
        if (questions.size() <= MEMORY_RECENT_QUESTION_WINDOW) {
            return;
        }

        RagConversationMemoryEntity memory = ragConversationMemoryRepository
            .findByUserIdAndConversationId(userId, conversationId)
            .orElseGet(() -> newConversationMemory(userId, conversationId));
        Long summarizedId = memory.getSummarizedQuestionId();
        List<RagQuestionEntity> summarizable = questions.subList(0, questions.size() - MEMORY_RECENT_QUESTION_WINDOW)
            .stream()
            .filter(question -> summarizedId == null || question.getId() > summarizedId)
            .toList();
        if (summarizable.isEmpty()) {
            return;
        }

        String updatedSummary = mergeConversationSummary(memory.getSummary(), summarizable);
        memory.setSummary(updatedSummary);
        memory.setSummarizedQuestionId(summarizable.get(summarizable.size() - 1).getId());
        ragConversationMemoryRepository.save(memory);
    }

    /** 创建新的会话记忆记录。 */
    private RagConversationMemoryEntity newConversationMemory(long userId, Long conversationId) {
        RagConversationMemoryEntity memory = new RagConversationMemoryEntity();
        memory.setUserId(userId);
        memory.setConversationId(conversationId);
        return memory;
    }

    /**
     * 将滑出窗口的问答追加到长期摘要中。
     *
     * <p>摘要保留问题和结论，不保存完整长回答；超过存储预算时裁掉最早部分。</p>
     */
    private String mergeConversationSummary(String existingSummary, List<RagQuestionEntity> questions) {
        List<String> lines = new ArrayList<>();
        if (existingSummary != null && !existingSummary.isBlank()) {
            lines.add(existingSummary.trim());
        }
        for (RagQuestionEntity question : questions) {
            lines.add(memoryLine(question));
        }
        return keepTailWithinBudget(String.join("\n", lines), MAX_MEMORY_STORAGE_CHARS);
    }

    /** 把一轮问答压缩成适合长期记忆保存的一行。 */
    private String memoryLine(RagQuestionEntity question) {
        String userQuestion = truncate(question.getQuestionText(), 500);
        String answer = truncate(cleanAnswerForMemory(question.getAnswerText()), 700);
        return "- 用户问：" + userQuestion + "；助手结论：" + answer;
    }

    /** 清理回答中的引用装饰，减少摘要里无关的来源文本。 */
    private String cleanAnswerForMemory(String answer) {
        if (answer == null) {
            return "";
        }
        return answer
            .replaceAll("(?s)\\n{2,}参考依据：.*$", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /** 保留字符串尾部预算，优先保存更新的会话信息。 */
    private String keepTailWithinBudget(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return "[较早摘要已截断]\n" + text.substring(Math.max(0, text.length() - maxChars));
    }

    /**
     * 为检索目的改写问题——处理代词引用和追问。
     * <p>
     * 当用户问"它有什么优缺点？"时，原始问题中的"它"无法直接检索。
     * 此方法会从对话历史中提取最近的主题词（如"索引"），将问题改写为
     * "索引有什么优缺点？"，从而提高检索准确性。
     */
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

    /**
     * 将追问中的代词替换为具体主题词。
     * 例如："它有什么优缺点" + 主题"索引" -> "索引有什么优缺点"
     */
    private String groundFollowUpQuestion(String question, String topic) {
        String grounded = question.trim()
            .replaceFirst("^(?:\\u90a3|\\u90a3\\u4e48|\\u6240\\u4ee5)?(?:\\u5b83|\\u8fd9\\u4e2a|\\u8be5|\\u8fd9|\\u4e0a\\u8ff0|\\u524d\\u9762)(\\u7684)?", Matcher.quoteReplacement(topic) + "$1")
            .replaceFirst("(?i)\\b(it|this|that|they|them|its|those|these)\\b", Matcher.quoteReplacement(topic));
        return grounded.trim();
    }

    /**
     * 判断是否为追问（包含代词引用）。
     * 特征：较短的问题中包含"它"、"这个"、"上述"等代词，或英文中的 "it"、"this"、"that" 等。
     */
    private boolean isFollowUpQuestion(String question) {
        String normalized = normalizeForTermMatch(question);
        if (normalized.length() <= 18 && containsAny(normalized, "它", "这个", "该", "上述", "前面", "刚才", "优缺点", "作用", "区别", "怎么", "为什么")) {
            return true;
        }
        return Pattern.compile("(?i)\\b(it|this|that|they|them|its|those|these)\\b").matcher(question).find();
    }

    /**
     * 从对话历史中提取最近讨论的主题词。
     * 优先从用户消息中提取（更准确反映用户意图），如果找不到再从所有消息中提取。
     */
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

    /**
     * 提取开放式话题查询：如"介绍一下 XXX"、"聊聊 XXX"、"XXX 怎么工作"。
     * 这些问题没有明确的定义/对比/功能等意图，但仍然需要提取核心术语用于检索。
     */
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

    /**
     * 从一段文本中提取对话主题词。
     * 依次尝试：关键词查询提取 -> 定义术语提取 -> 技术术语正则匹配。
     */
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

    // ========== 聊天模式判断与资料验证 ==========

    /** 判断是否为通用聊天模式（不基于资料的自由问答） */
    private boolean isGeneralChat(ChatRequest request) {
        return "GENERAL".equalsIgnoreCase(String.valueOf(request.mode()).trim());
    }

    /** 判断是否为资料模式聊天（基于特定学习资料的问答） */
    private boolean isMaterialChat(ChatRequest request) {
        return "MATERIAL".equalsIgnoreCase(String.valueOf(request.mode()).trim());
    }

    /**
     * 验证当前资料是否可用于聊天。
     * 检查：资料ID不为空、资料存在、资料属于当前用户、资料已成功解析。
     */
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

    // ========== 问答历史管理 ==========

    /**
     * 获取用户的聊天历史列表。
     * 按对话分组，每个对话只返回最新的一条问答记录作为代表。
     *
     * @param userId 用户 ID
     * @return 历史记录列表（按置顶和创建时间排序）
     */
    @Transactional(readOnly = true)
    public List<RagHistoryItemResponse> history(long userId) {
        return latestQuestionsByConversation(userId).stream()
            .map(question -> toHistoryItem(userId, question))
            .toList();
    }

    /**
     * 获取某个问答记录的完整对话详情。
     * 返回该对话中所有问答轮次的消息列表、来源引用、收藏状态等。
     */
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

    /**
     * 获取某份资料最近一段问答会话。
     * 阅读器打开资料时用它恢复上次边读边问上下文；没有历史时返回 null。
     */
    @Transactional(readOnly = true)
    public RagHistoryDetailResponse latestMaterialHistory(long userId, long materialId) {
        validateCurrentMaterialForChat(userId, materialId);
        return latestMaterialHistoryQuestion(userId, materialId)
            .map(question -> historyDetail(userId, question.getId()))
            .orElse(null);
    }

    /**
     * 获取某份资料的历史问答会话列表。
     * 只返回每个 conversation 的最新一条，用于阅读器问答区的历史弹窗。
     */
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

    /** 重命名历史记录的标题 */
    @Transactional
    public RagHistoryItemResponse renameHistory(long userId, long questionId, String title) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "History not found"));
        question.setTitle(normalizeHistoryTitle(title, question.getQuestionText()));
        return toHistoryItem(userId, ragQuestionRepository.save(question));
    }

    /** 切换历史记录的置顶状态 */
    @Transactional
    public RagHistoryItemResponse togglePinHistory(long userId, long questionId) {
        RagQuestionEntity question = ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "History not found"));
        question.setPinned(!question.isPinned());
        return toHistoryItem(userId, ragQuestionRepository.save(question));
    }

    // ========== 用户反馈 ==========

    /**
     * 提交用户对问答结果的反馈（点赞/踩 + 评论）。
     * 如果已有反馈则更新，否则新建。rating 只能是 1（赞）或 -1（踩）。
     */
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

    // ========== RAG 质量评估 ==========

    /**
     * 对单次问答进行 RAG 质量评估。
     * <p>
     * 评估指标：
     * <ul>
     *   <li><b>忠实度（faithfulness）</b>：回答中的关键词是否能在来源上下文中找到支撑（55%权重）</li>
     *   <li><b>上下文相关性（contextRelevance）</b>：检索到的来源是否与问题相关（45%权重）</li>
     *   <li><b>综合分数（overall）</b>：以上两项的加权平均</li>
     * </ul>
     * <p>
     * 评定结论：
     * <ul>
     *   <li>PASS：综合 >= 0.72 且忠实度 >= 0.65 且相关性 >= 0.55</li>
     *   <li>WARN：综合 >= 0.42 且忠实度 >= 0.35 且相关性 >= 0.30</li>
     *   <li>FAIL：低于 WARN 阈值</li>
     * </ul>
     */
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

    /**
     * 获取最新的评估结果。如果还没有评估过，则自动触发一次评估。
     */
    @Transactional
    public RagEvaluationResponse latestEvaluation(long userId, long questionId) {
        ragQuestionRepository.findByIdAndUserId(questionId, userId)
            .orElseThrow(() -> new BusinessException(404, "Question not found"));
        return ragEvaluationRepository.findByQuestionIdAndUserId(questionId, userId)
            .map(this::toEvaluationResponse)
            .orElseGet(() -> evaluateHistory(userId, questionId));
    }

    // ========== 评估套件（批量测试） ==========

    /**
     * 运行一次性评估套件：对多个测试用例逐个执行 RAG 问答 + 评估。
     * 每个测试用例包含问题、资料ID、期望的关键词等，用于系统性地测试 RAG 质量。
     */
    @Transactional
    public RagEvaluationSuiteResponse runEvaluationSuite(long userId, RagEvaluationSuiteRequest request) {
        if (request == null || request.cases() == null || request.cases().isEmpty()) {
            throw new BusinessException(400, "evaluation cases are required");
        }
        return runEvaluationCases(userId, request.cases());
    }

    /** 获取用户的评估套件列表 */
    @Transactional(readOnly = true)
    public List<RagEvaluationSuiteSummaryResponse> evaluationSuites(long userId) {
        return ragEvaluationSuiteRepository.findByUserIdOrderByUpdatedAtDesc(userId).stream()
            .map(suite -> toSuiteSummaryResponse(
                suite,
                ragEvaluationSuiteCaseRepository.findBySuiteIdOrderByCaseIndexAsc(suite.getId()).size()
            ))
            .toList();
    }

    /** 获取评估套件详情（包含测试用例列表和最新运行结果） */
    @Transactional(readOnly = true)
    public RagEvaluationSuiteDetailResponse evaluationSuiteDetail(long userId, long suiteId) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        return toSuiteDetailResponse(suite);
    }

    /** 新建评估套件 */
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

    /** 更新评估套件（替换名称、描述和测试用例） */
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

    /** 删除评估套件及其所有用例和运行记录 */
    @Transactional
    public void deleteEvaluationSuite(long userId, long suiteId) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        ragEvaluationSuiteRunRepository.deleteBySuiteId(suite.getId());
        ragEvaluationSuiteCaseRepository.deleteBySuiteId(suite.getId());
        ragEvaluationSuiteRepository.delete(suite);
    }

    /** 运行已保存的评估套件 */
    @Transactional
    public RagEvaluationSuiteRunResponse runSavedEvaluationSuite(long userId, long suiteId) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        return runEvaluationSuiteEntity(suite, LocalDateTime.now());
    }

    /** 获取评估套件的历史运行记录列表 */
    @Transactional(readOnly = true)
    public List<RagEvaluationSuiteRunResponse> evaluationSuiteRuns(long userId, long suiteId) {
        ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        return ragEvaluationSuiteRunRepository.findBySuiteIdAndUserIdOrderByCreatedAtDesc(suiteId, userId).stream()
            .map(this::toSuiteRunResponse)
            .toList();
    }

    /** 更新评估套件的定时运行配置（开启/关闭定时、设置间隔时间） */
    @Transactional
    public RagEvaluationSuiteDetailResponse updateEvaluationSuiteSchedule(long userId, long suiteId, RagEvaluationSuiteScheduleRequest request) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findByIdAndUserId(suiteId, userId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        applySchedule(suite, request.scheduled(), request.intervalHours(), LocalDateTime.now());
        return toSuiteDetailResponse(ragEvaluationSuiteRepository.save(suite));
    }

    /** 查询所有到期需要运行的定时评估套件（由定时任务调度器调用） */
    @Transactional(readOnly = true)
    public List<RagEvaluationSuiteEntity> dueScheduledEvaluationSuites(LocalDateTime now) {
        return ragEvaluationSuiteRepository.findByScheduledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now);
    }

    /** 运行定时评估套件（由定时任务调度器调用） */
    @Transactional
    public RagEvaluationSuiteRunResponse runScheduledEvaluationSuite(long suiteId, LocalDateTime now) {
        RagEvaluationSuiteEntity suite = ragEvaluationSuiteRepository.findById(suiteId)
            .orElseThrow(() -> new BusinessException(404, "evaluation suite not found"));
        if (!Boolean.TRUE.equals(suite.getScheduled())) {
            throw new BusinessException(400, "evaluation suite is not scheduled");
        }
        return runEvaluationSuiteEntity(suite, now == null ? LocalDateTime.now() : now);
    }

    /**
     * 执行评估套件实体：加载用例、运行评估、保存运行记录、更新套件统计。
     * 如果套件配置了定时运行，会自动计算下次运行时间。
     */
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

    /**
     * 运行评估用例列表：对每个测试用例执行 RAG 问答 + 评估 + 关键词覆盖率检查。
     * <p>
     * 通过判定逻辑：
     * <ul>
     *   <li>如果用例指定了期望关键词：回答和来源的关键词覆盖率都 >= 70% 判定通过</li>
     *   <li>如果没有指定期望关键词：使用评估的 PASS/WARN/FAIL 结论判定</li>
     * </ul>
     *
     * @return 评估套件的汇总结果（通过率、平均分等）
     */
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

    // ========== 历史记录删除与清理 ==========

    /**
     * 删除指定问答记录及其所属的整个对话。
     * 同时删除关联的来源、反馈、评估和收藏数据。
     */
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

    /** 清空用户的所有聊天历史（包括所有关联数据） */
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

    // ========== 资料摘要 ==========

    /**
     * 为资料生成通用结构化总结。
     * <p>
     * 流程：按章节/标题聚合所有片段 -> 压缩为全量章节概览 -> 调用 LLM 生成结构化总结 -> 保存。
     * 如果 LLM 调用失败或未返回 JSON，使用本地规则生成可读的结构化总结。
     */
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

        String summaryType = normalizeSummaryType(request.summaryType());
        List<SummaryGroup> groups = buildSummaryGroups(chunks);
        List<String> sectionBriefs = buildSectionBriefs(groups);
        List<SummarySourceResponse> sources = buildSummarySources(material, groups);
        List<LlmImage> summaryImages = loadSummaryImages(material, chunks);
        log.info(
            "Generating material summary materialId={} type={} groups={} briefs={} sources={} images={}",
            material.getId(), summaryType, groups.size(), sectionBriefs.size(), sources.size(), summaryImages.size()
        );
        LlmCompletion completion = thirdPartyLlmClient
            .summarizeStructured(material.getTitle(), summaryTypeLabel(summaryType), sectionBriefs, summaryImages)
            .orElseGet(() -> new LlmCompletion(buildCleanSummary(material, chunks), "local-rag-demo"));
        StructuredSummary structured = parseStructuredSummary(completion.content(), material, groups, sources);

        MaterialSummaryEntity entity = new MaterialSummaryEntity();
        entity.setMaterialId(material.getId());
        entity.setUserId(userId);
        entity.setSummaryText(structured.summary());
        entity.setSummaryType(summaryType);
        entity.setModelName(completion.modelName());
        entity.setStructuredJson(writeJson(structured.sections()));
        entity.setSourcesJson(writeJson(sources));
        MaterialSummaryEntity saved = materialSummaryRepository.save(entity);

        material.setSummaryStatus(MaterialSummaryStatus.SUCCESS);
        learningMaterialRepository.save(material);

        return toSummaryResponse(material, saved, sources.size());
    }

    /** 获取资料的最新摘要 */
    @Transactional(readOnly = true)
    public RagSummaryResponse latestSummary(long userId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElseThrow(() -> new BusinessException(404, "material not found"));
        MaterialSummaryEntity summary = materialSummaryRepository.findFirstByMaterialIdAndUserIdOrderByCreatedAtDesc(materialId, userId)
            .orElseThrow(() -> new BusinessException(404, "summary not found"));
        return toSummaryResponse(material, summary, null);
    }

    /** 获取资料的摘要历史列表（可能有多次生成的摘要） */
    @Transactional(readOnly = true)
    public List<RagSummaryResponse> summaryHistory(long userId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, userId)
            .orElseThrow(() -> new BusinessException(404, "material not found"));
        return materialSummaryRepository.findByMaterialIdAndUserIdOrderByCreatedAtDesc(materialId, userId).stream()
            .map(summary -> toSummaryResponse(material, summary, null))
            .toList();
    }

    /** 更新用户整理版摘要 */
    @Transactional
    public RagSummaryResponse updateSummaryNote(long userId, long summaryId, UpdateSummaryNoteRequest request) {
        MaterialSummaryEntity summary = materialSummaryRepository.findById(summaryId)
            .filter(item -> item.getUserId() != null && item.getUserId().longValue() == userId)
            .orElseThrow(() -> new BusinessException(404, "summary not found"));
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(summary.getMaterialId(), userId)
            .orElseThrow(() -> new BusinessException(404, "material not found"));
        summary.setUserNote(request == null || request.userNote() == null ? "" : request.userNote());
        MaterialSummaryEntity saved = materialSummaryRepository.save(summary);
        return toSummaryResponse(material, saved, null);
    }

    private RagSummaryResponse toSummaryResponse(
        LearningMaterialEntity material,
        MaterialSummaryEntity summary,
        Integer sourceCountOverride
    ) {
        List<SummarySourceResponse> sources = readSummarySources(summary.getSourcesJson());
        List<SummarySectionResponse> sections = readSummarySections(summary.getStructuredJson(), sources);
        int sourceCount = sourceCountOverride == null ? sources.size() : sourceCountOverride;
        if (sourceCount <= 0) {
            sourceCount = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId()).size();
        }
        return new RagSummaryResponse(
            summary.getId(),
            material.getId(),
            material.getTitle(),
            summary.getSummaryText(),
            summary.getSummaryType(),
            summary.getModelName(),
            sourceCount,
            summary.getCreatedAt() == null ? null : summary.getCreatedAt().format(DATETIME_FORMATTER),
            sections,
            sources,
            summary.getUserNote()
        );
    }

    private String normalizeSummaryType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "BRIEF", "DETAILED", "OUTLINE", "REVIEW", "ACTION" -> normalized;
            default -> "GENERAL";
        };
    }

    private String summaryTypeLabel(String summaryType) {
        return switch (summaryType) {
            case "BRIEF" -> "简洁摘要";
            case "DETAILED" -> "详细总结";
            case "OUTLINE" -> "章节/标题提纲";
            case "REVIEW" -> "复习巩固版";
            case "ACTION" -> "行动清单/决策版";
            default -> "通用结构化总结";
        };
    }

    private List<SummaryGroup> buildSummaryGroups(List<MaterialChunkEntity> chunks) {
        Map<String, SummaryGroupBuilder> groups = new LinkedHashMap<>();
        for (MaterialChunkEntity chunk : chunks) {
            String title = summaryGroupTitle(chunk);
            SummaryGroupBuilder builder = groups.computeIfAbsent(title, SummaryGroupBuilder::new);
            builder.chunks().add(chunk);
        }
        return groups.values().stream()
            .map(builder -> new SummaryGroup(builder.title(), List.copyOf(builder.chunks())))
            .toList();
    }

    private String summaryGroupTitle(MaterialChunkEntity chunk) {
        String hierarchy = firstNonBlank(chunk.getHierarchyPath(), chunk.getSectionTitle());
        if (hierarchy != null) {
            return hierarchy.length() <= 120 ? hierarchy : hierarchy.substring(0, 120);
        }
        int index = chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex();
        return "片段 " + (index + 1);
    }

    private List<String> buildSectionBriefs(List<SummaryGroup> groups) {
        List<String> briefs = new ArrayList<>();
        int usedChars = 0;
        for (SummaryGroup group : groups) {
            String joined = group.chunks().stream()
                .map(chunk -> firstNonBlank(chunk.getSummary(), chunk.getChunkText()))
                .filter(text -> text != null && !text.isBlank())
                .map(this::summaryExcerpt)
                .limit(4)
                .collect(Collectors.joining("\n"));
            if (joined.isBlank()) {
                continue;
            }
            String brief = "【" + group.title() + "】\n" + joined;
            if (usedChars + brief.length() > MAX_SUMMARY_INPUT_CHARS) {
                int remaining = Math.max(0, MAX_SUMMARY_INPUT_CHARS - usedChars);
                if (remaining < 240) {
                    break;
                }
                brief = brief.substring(0, Math.min(brief.length(), remaining));
            }
            briefs.add(brief);
            usedChars += brief.length();
        }
        return briefs;
    }

    private List<SummarySourceResponse> buildSummarySources(LearningMaterialEntity material, List<SummaryGroup> groups) {
        List<SummarySourceResponse> sources = new ArrayList<>();
        for (SummaryGroup group : groups) {
            group.chunks().stream()
                .filter(chunk -> chunk.getChunkText() != null && !chunk.getChunkText().isBlank())
                .limit(2)
                .map(chunk -> new SummarySourceResponse(
                    material.getId(),
                    chunk.getId(),
                    group.title(),
                    chunk.getPageNo(),
                    chunk.getChunkIndex(),
                    summaryExcerpt(chunk.getChunkText())
                ))
                .forEach(sources::add);
        }
        return sources;
    }

    private List<LlmImage> loadSummaryImages(LearningMaterialEntity material, List<MaterialChunkEntity> chunks) {
        if (material.getStoragePath() == null) {
            return List.of();
        }
        boolean needsImages = chunks.stream().anyMatch(chunk -> isTextExtractionPlaceholder(chunk.getChunkText()));
        if (!needsImages && material.getSourceType() != MaterialSourceType.PDF) {
            return List.of();
        }
        List<LlmImage> images = new ArrayList<>();
        Set<Integer> pageNos = chunks.stream()
            .map(MaterialChunkEntity::getPageNo)
            .filter(pageNo -> pageNo != null && pageNo > 0)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        for (Integer pageNo : pageNos) {
            if (images.size() >= MAX_SUMMARY_IMAGES_PER_REQUEST) {
                break;
            }
            LlmImage image = loadImage(renderPdfPageAsset(sourcePath, "page-" + pageNo + ".png"));
            if (image != null) {
                images.add(image);
            }
        }
        return images;
    }

    private String summaryExcerpt(String text) {
        String cleaned = cleanExcerptText(text)
            .replaceAll("第\\s*\\d+\\s*页暂无可抽取文本[；;，,。]?.*", "")
            .replaceAll("原页图片将在预览或多模态问答时按需生成[。]?.*", "")
            .replaceAll("已保留原页预览用于阅读和问答依据[。]?.*", "")
            .trim();
        if (cleaned.isBlank() && isTextExtractionPlaceholder(text)) {
            Integer pageNo = extractPlaceholderPageNo(text);
            return pageNo == null ? "该页为图片页面，需结合页面原图总结。" : "第 " + pageNo + " 页为图片页面，需结合页面原图总结。";
        }
        return cleaned.length() <= 160 ? cleaned : cleaned.substring(0, 160) + "...";
    }

    private boolean isTextExtractionPlaceholder(String text) {
        String value = text == null ? "" : text;
        return value.contains("暂无可抽取文本") || value.contains("无可抽取文本");
    }

    private Integer extractPlaceholderPageNo(String text) {
        Matcher matcher = Pattern.compile("第\\s*(\\d+)\\s*页").matcher(text == null ? "" : text);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private StructuredSummary parseStructuredSummary(
        String content,
        LearningMaterialEntity material,
        List<SummaryGroup> groups,
        List<SummarySourceResponse> sources
    ) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(extractJsonObject(content));
            String summary = root.path("summary").asText("");
            List<SummarySectionResponse> sections = new ArrayList<>();
            JsonNode sectionNodes = root.path("sections");
            if (sectionNodes.isArray()) {
                for (JsonNode node : sectionNodes) {
                    String title = node.path("title").asText("").trim();
                    List<String> items = new ArrayList<>();
                    JsonNode itemNodes = node.path("items");
                    if (itemNodes.isArray()) {
                        for (JsonNode item : itemNodes) {
                            String text = item.asText("").trim();
                            if (!text.isBlank()) {
                                items.add(text);
                            }
                        }
                    }
                    if (!title.isBlank() && !items.isEmpty()) {
                        sections.add(new SummarySectionResponse(title, items, sectionSources(title, sources)));
                    }
                }
            }
            if (!summary.isBlank() && !sections.isEmpty()) {
                return new StructuredSummary(cleanAnswerText(summary), sections);
            }
        } catch (Exception ignored) {
            // LLM 偶尔会返回非 JSON；下面使用本地结构化兜底。
        }
        return fallbackStructuredSummary(material, groups, sources, content);
    }

    private StructuredSummary fallbackStructuredSummary(
        LearningMaterialEntity material,
        List<SummaryGroup> groups,
        List<SummarySourceResponse> sources,
        String rawContent
    ) {
        String summary = cleanAnswerText(rawContent);
        if (summary.isBlank()) {
            summary = buildCleanSummary(material, groups.stream().flatMap(group -> group.chunks().stream()).toList());
        }
        List<String> structureItems = groups.stream()
            .limit(8)
            .map(group -> group.title() + "：" + group.chunks().stream()
                .findFirst()
                .map(chunk -> excerpt(firstNonBlank(chunk.getSummary(), chunk.getChunkText())))
                .orElse(""))
            .filter(item -> !item.isBlank())
            .toList();
        List<SummarySectionResponse> sections = List.of(
            new SummarySectionResponse("核心摘要", List.of(summary), sources.stream().limit(3).toList()),
            new SummarySectionResponse("结构脉络", structureItems, sources.stream().limit(8).toList())
        );
        return new StructuredSummary(summary, sections);
    }

    private String extractJsonObject(String content) {
        String value = content == null ? "" : content.trim();
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private List<SummarySourceResponse> sectionSources(String title, List<SummarySourceResponse> sources) {
        if (sources.isEmpty()) {
            return List.of();
        }
        String normalized = title == null ? "" : title.toLowerCase(Locale.ROOT);
        if (normalized.contains("结构") || normalized.contains("章节") || normalized.contains("脉络")) {
            return sources.stream().limit(8).toList();
        }
        return sources.stream().limit(4).toList();
    }

    private List<SummarySourceResponse> readSummarySources(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<SummarySourceResponse> sources = new ArrayList<>();
            for (JsonNode node : root) {
                sources.add(new SummarySourceResponse(
                    node.path("materialId").isMissingNode() ? null : node.path("materialId").asLong(),
                    node.path("chunkId").isMissingNode() ? null : node.path("chunkId").asLong(),
                    node.path("title").asText(null),
                    node.path("pageNo").isNull() || node.path("pageNo").isMissingNode() ? null : node.path("pageNo").asInt(),
                    node.path("chunkIndex").isNull() || node.path("chunkIndex").isMissingNode() ? null : node.path("chunkIndex").asInt(),
                    node.path("excerpt").asText("")
                ));
            }
            return sources;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<SummarySectionResponse> readSummarySections(String json, List<SummarySourceResponse> fallbackSources) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(json);
            if (!root.isArray()) {
                return List.of();
            }
            List<SummarySectionResponse> sections = new ArrayList<>();
            for (JsonNode node : root) {
                String title = node.path("title").asText("").trim();
                List<String> items = new ArrayList<>();
                JsonNode itemNodes = node.path("items");
                if (itemNodes.isArray()) {
                    for (JsonNode item : itemNodes) {
                        String text = item.asText("").trim();
                        if (!text.isBlank()) {
                            items.add(text);
                        }
                    }
                }
                if (!title.isBlank() && !items.isEmpty()) {
                    sections.add(new SummarySectionResponse(title, items, sectionSources(title, fallbackSources)));
                }
            }
            return sections;
        } catch (Exception exception) {
            return List.of();
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        if (second != null && !second.isBlank()) {
            return second.trim();
        }
        return null;
    }

    // ========== 收藏管理 ==========

    /**
     * 添加收藏：将指定问答记录加入用户的收藏夹。
     * 如果已收藏则更新（幂等操作）。
     */
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

    /** 取消收藏 */
    @Transactional
    public void deleteFavorite(long userId, long favoriteId) {
        UserFavoriteEntity favorite = userFavoriteRepository.findByIdAndUserId(favoriteId, userId)
            .orElseThrow(() -> new BusinessException(404, "收藏不存在"));
        userFavoriteRepository.delete(favorite);
    }

    /** 获取用户的收藏列表（按收藏时间倒序） */
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

    // ========== 响应转换辅助方法 ==========

    /** 将问答实体转换为历史列表项响应 */
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

    /**
     * 将问答实体转换为历史详情响应（包含完整对话消息列表）。
     *
     * <p>历史列表只展示会话的最新一条问答；用户点开详情时，才通过
     * conversationQuestions 把同一 conversationId 下的全部轮次拼成 messages。
     * sources 仍然只代表最新回答的引用来源，前端会把它挂在最后一条 assistant 消息上。</p>
     */
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

    /** 将反馈实体转换为响应 */
    private RagFeedbackResponse toFeedbackResponse(RagFeedbackEntity feedback) {
        return new RagFeedbackResponse(
            feedback.getId(),
            feedback.getQuestionId(),
            feedback.getRating(),
            feedback.getComment(),
            feedback.getUpdatedAt() == null ? null : feedback.getUpdatedAt().format(DATETIME_FORMATTER)
        );
    }

    /** 将评估实体转换为评估响应 */
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

    /** 将评估套件实体转换为摘要响应（不含用例详情） */
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

    /** 将评估套件实体转换为详情响应（包含用例列表和最新运行结果） */
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

    /** 将评估套件运行记录实体转换为响应 */
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

    // ========== 评估辅助方法 ==========

    /** 从文本中提取有意义的关键词用于评估（limit 为最大数量） */
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

    /** 计算关键词在文本中的覆盖率（用于评估忠实度和相关性） */
    private double scoreTermCoverage(String text, List<String> terms) {
        if (terms.isEmpty()) {
            return 1.0;
        }
        String normalizedText = normalizeForTermMatch(text);
        long matched = terms.stream().filter(normalizedText::contains).count();
        return (double) matched / terms.size();
    }

    /**
     * 根据评估分数生成评定结论。
     * PASS = 合格，WARN = 需要关注，FAIL = 不合格。
     */
    private String evaluationVerdict(double overallScore, double faithfulnessScore, double contextRelevanceScore) {
        if (overallScore >= 0.72 && faithfulnessScore >= 0.65 && contextRelevanceScore >= 0.55) {
            return "PASS";
        }
        if (overallScore >= 0.42 && faithfulnessScore >= 0.35 && contextRelevanceScore >= 0.30) {
            return "WARN";
        }
        return "FAIL";
    }

    /**
     * 构建评估证据文本：记录哪些关键词被来源支持、哪些缺失。
     * 格式：sources=数量; matchedQuestionTerms=...; supportedAnswerTerms=...; missingAnswerTerms=...
     */
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

    /**
     * 计算期望关键词覆盖率：检查期望出现的关键词是否在文本中实际出现。
     * 用于评估套件中检查回答/来源是否覆盖了期望的关键词。
     *
     * @return TermCoverage 包含覆盖率分数和缺失的关键词列表
     */
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

    // ========== 对话管理辅助方法 ==========

    /** 获取用户每个对话的最新一条问答记录（用于历史列表展示） */
    private List<RagQuestionEntity> latestQuestionsByConversation(long userId) {
        Map<Long, RagQuestionEntity> latestByConversation = new LinkedHashMap<>();
        for (RagQuestionEntity question : ragQuestionRepository.findByUserIdOrderByPinnedDescCreatedAtDesc(userId)) {
            Long conversationId = effectiveConversationId(question);
            latestByConversation.putIfAbsent(conversationId, question);
        }
        return latestByConversation.values().stream().toList();
    }

    /**
     * 查询资料相关问答记录。
     * 新记录直接使用 rag_question.material_id；旧记录通过来源表中的 material_id 兜底。
     */
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
            .sorted((left, right) -> compareCreatedAtDesc(left, right))
            .toList();
    }

    /** 获取资料最近一条问答记录，用于恢复阅读器问答会话。 */
    private Optional<RagQuestionEntity> latestMaterialHistoryQuestion(long userId, long materialId) {
        return materialHistoryQuestions(userId, materialId).stream().findFirst();
    }

    /** 按创建时间倒序比较，兼容极少数缺失时间的旧记录。 */
    private int compareCreatedAtDesc(RagQuestionEntity left, RagQuestionEntity right) {
        if (left.getCreatedAt() == null && right.getCreatedAt() == null) return 0;
        if (left.getCreatedAt() == null) return 1;
        if (right.getCreatedAt() == null) return -1;
        return right.getCreatedAt().compareTo(left.getCreatedAt());
    }

    /**
     * 将问答列表转为对话消息列表（交替的 user/assistant 消息）。
     *
     * <p>数据库一行 rag_question 同时包含一次用户提问和一次 AI 回答；
     * 前端聊天线程需要的是逐条消息，所以这里把每行拆成 user + assistant 两条。
     * 图片和临时资料只挂在 user 消息上，assistant 消息只保留回答文本。</p>
     */
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

    /** 获取同一对话中的所有问答记录（按创建时间排序） */
    private List<RagQuestionEntity> questionsInConversation(long userId, RagQuestionEntity question) {
        Long conversationId = effectiveConversationId(question);
        List<RagQuestionEntity> conversationQuestions = ragQuestionRepository
            .findByUserIdAndConversationIdOrderByCreatedAtAsc(userId, conversationId);
        if (conversationQuestions.isEmpty()) {
            return List.of(question);
        }
        return conversationQuestions;
    }

    /**
     * 解析对话 ID：如果用户指定了 conversationId，查找该问答并获取其有效的对话 ID。
     *
     * <p>前端可能传“会话 ID”，也可能传该会话里某一条问题 ID。这里统一查库确认
     * 记录属于当前用户，再映射到 effectiveConversationId，防止用户把消息追加到
     * 不属于自己的会话。</p>
     */
    private Long resolveConversationId(long userId, Long requestedConversationId) {
        if (requestedConversationId == null || requestedConversationId <= 0) {
            return null;
        }
        return ragQuestionRepository.findByIdAndUserId(requestedConversationId, userId)
            .map(this::effectiveConversationId)
            .orElse(null);
    }

    /** 只有资料问答写 material_id，通用问答保持为空，避免历史恢复串到非资料会话。 */
    private Long materialQuestionId(ChatRequest request) {
        if (request == null || request.materialId() == null || !isMaterialChat(request)) {
            return null;
        }
        return request.materialId();
    }

    /**
     * 确保问答记录有有效的对话 ID。
     *
     * <p>新对话的第一条消息在插入前还没有自增 ID，因此 conversationId 先为空；
     * 插入成功后再把自己的主键写回 conversation_id。后续轮次只要传这个 ID，
     * 就会被归入同一组历史会话。</p>
     */
    private RagQuestionEntity ensureConversationId(RagQuestionEntity question) {
        if (question.getConversationId() == null) {
            question.setConversationId(question.getId());
            return ragQuestionRepository.save(question);
        }
        return question;
    }

    /** 获取有效的对话 ID：如果 conversationId 为空则返回问答记录自己的 ID */
    private Long effectiveConversationId(RagQuestionEntity question) {
        return question.getConversationId() == null ? question.getId() : question.getConversationId();
    }

    /**
     * 生成对话标题：从问题文本中截取前 28 个字符作为标题。
     * 超出部分用 "..." 省略。
     */
    private String buildConversationTitle(String questionText) {
        String normalized = normalizeHistoryTitle(questionText, questionText);
        if (normalized.length() <= 28) {
            return normalized;
        }
        return normalized.substring(0, 28) + "...";
    }

    /** 规范化历史标题：去除多余空白，空标题时使用"未命名会话" */
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

    // ========== 来源引用管理 ==========

    /**
     * 保存问答引用的来源片段信息。
     * 记录每个回答使用了哪些资料的哪些片段，方便前端展示来源引用。
     */
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
        return "可以从这些资料片段入手回答“" + question + "”：\n\n"
            + evidence;
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
        return "围绕“" + question + "”，可以这样回答：" + mainEvidence
            + "。\n\n"
            + "原文依据：\n" + references;
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

    // ========== 回答后处理：装饰与格式化 ==========

    /**
     * 装饰资料模式的回答：添加引用依据（资料名称 + 页码）。
     * <p>
     * 处理逻辑：
     * <ul>
     *   <li>如果用户选中了文本提问，直接返回清理后的回答</li>
     *   <li>如果没有检索到相关片段且不是闲聊，返回"未找到依据"的提示</li>
     *   <li>闲聊问题去除无来源提示后直接返回</li>
     *   <li>正常回答：在末尾添加"资料依据"段落，列出引用的资料名称和页码</li>
     * </ul>
     */
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
        return "当前资料里没有检索到足够依据来回答这个问题。\n\n"
            + "问题：" + (question == null ? "" : question.trim()) + "\n\n"
            + "可以补充更相关的章节、选中原文后提问，或换成资料中出现的关键词再试。";
    }

    private boolean isHomeworkStyle(String answerStyle) {
        return "HOMEWORK".equalsIgnoreCase(String.valueOf(answerStyle).trim());
    }

    /** 装饰通用聊天模式的回答：去除资料相关的提示信息 */
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

    /**
     * 装饰超长文档单段回答。
     *
     * <p>长文生成可能发生在资料模式或通用模式中，但它本质是创作/续写任务。
     * 这里只清理模型输出，不追加资料引用，也不因检索片段为空而改写为"资料不足"。</p>
     */
    private String decorateLongDocumentAnswer(String content) {
        String answer = cleanAnswerText(content);
        return answer.isBlank() ? "未生成有效回答。" : answer;
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

    /** 判断是否为闲聊问题（你好、谢谢、在吗等） */
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

    // ========== BM25 关键词检索 ==========

    /**
     * BM25 关键词检索：基于倒排索引的传统信息检索方法。
     * <p>
     * BM25（Best Matching 25）是一种经典的信息检索算法，通过计算查询词
     * 在文档中的词频（TF）和逆文档频率（IDF）来评分。
     * <p>
     * 与向量检索互补：
     * <ul>
     *   <li>向量检索擅长语义相似性（"快排"能匹配"快速排序算法"）</li>
     *   <li>BM25 擅长精确关键词匹配（"TCP"只匹配包含"TCP"的片段）</li>
     * </ul>
     *
     * @return 按 BM25 分数降序排列的所有片段
     */
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

    /**
     * 获取或创建 BM25 评分器。
     * 使用缓存避免每次查询都重建倒排索引（构建索引开销较大）。
     * 缓存满时清空所有缓存重新构建。
     */
    private Bm25Scorer bm25Scorer(
        long userId,
        Long materialId,
        List<MaterialChunks> materialChunks,
        List<Bm25Scorer.ChunkData> allChunkData
    ) {
        String cacheKey = bm25CacheKey(userId, materialId, materialChunks);
        Bm25IndexCacheEntry cached = bm25IndexCache.get(cacheKey);
        if (cached != null) {
            // BM25 构建成本高，资料版本未变时直接复用倒排统计。
            return cached.scorer();
        }
        if (bm25IndexCache.size() >= BM25_CACHE_MAX_ENTRIES) {
            // 缓存空间达到上限时整体清空，避免长期持有旧资料索引。
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

    // ========== 向量语义检索 ==========

    /**
     * 向量语义检索：将问题和片段都转为向量，计算余弦相似度。
     * <p>
     * 检索流程：
     * <ol>
     *   <li>调用 EmbeddingClient 将问题文本转为向量</li>
     *   <li>优先使用外部向量数据库（如 Milvus）检索（速度更快）</li>
     *   <li>如果外部向量数据库未配置，回退到本地遍历计算余弦相似度</li>
     *   <li>根据问题长度动态调整相似度阈值（短问题降低阈值，长问题提高阈值）</li>
     * </ol>
     *
     * @return 按相似度降序排列的片段列表
     */
    private List<ScoredChunk> findVectorScoredChunks(long userId, String question, Long materialId) {
        Optional<List<Double>> questionEmbedding = embeddingClient.embedQuery(question);
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

    /**
     * 使用外部向量数据库检索：将问题向量发送到向量数据库进行 ANN（近似最近邻）搜索。
     * 比本地遍历快得多，适合大规模资料库。
     * 如果向量数据库未配置或无结果，返回空列表（调用方会回退到本地检索）。
     */
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

    /**
     * 根据问题长度动态调整向量相似度阈值。
     * <p>
     * 短问题（<=8字符）：降低阈值 0.08（因为短问题的向量表示不够精确，需要放宽匹配）
     * 长问题（>=80字符）：提高阈值 0.03（长问题向量更精确，可以更严格匹配）
     * 其他：使用配置的默认阈值
     */
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

    /**
     * 从排序后的候选片段中选出最终的 topK 个片段。
     * <p>
     * 选择策略：
     * <ul>
     *   <li>分数 <= 0 的片段被跳过</li>
     *   <li>如果使用 BM25 分数（>1.0），只保留 BM25 精确匹配的片段</li>
     *   <li>超过 3 个片段后，避免来自同一页的重复片段（增加多样性）</li>
     *   <li>最多选取 topK 个片段</li>
     * </ul>
     */
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

    /**
     * 限制上下文片段的总字符数，防止超出 LLM 的上下文窗口。
     * 最多选取 topK 个片段，且总字符数不超过 MAX_CONTEXT_CHARS（10000字符）。
     */
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

    /** 解析存储在数据库中的嵌入向量 JSON 字符串为 Double 列表 */
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

    /**
     * 计算两个向量的余弦相似度。
     * <p>
     * 公式：cos(A, B) = (A . B) / (|A| * |B|)
     * <p>
     * 结果范围：-1 到 1（对于文本嵌入向量通常在 0 到 1 之间）。
     * 1 表示完全相同方向，0 表示正交（无关），-1 表示完全相反。
     * 返回 NaN 表示向量维度不匹配或零向量。
     */
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

    // ========== 图片处理常量和正则 ==========

    /** 匹配资料中嵌入图片的标记：[[material-image:xxx.png]] */
    private static final java.util.regex.Pattern IMAGE_MARKER_PATTERN =
        java.util.regex.Pattern.compile("\\[\\[material-image:[^\\]]+\\]\\]\\s*");
    /** 用于提取图片文件名的捕获组模式 */
    private static final java.util.regex.Pattern IMAGE_MARKER_EXTRACT_PATTERN =
        java.util.regex.Pattern.compile("\\[\\[material-image:([^\\]]+)\\]\\]");
    /** 匹配 OCR 标记的模式 */
    private static final java.util.regex.Pattern IMAGE_OCR_PATTERN =
        java.util.regex.Pattern.compile("\\[image ocr:[^\\]]*\\]\\s*");
    /** 每个片段最多加载的图片数量 */
    private static final int MAX_IMAGES_PER_CHUNK = 3;
    /** 每次请求最多发送给 LLM 的图片总数 */
    private static final int MAX_IMAGES_PER_REQUEST = 10;
    /** 图片发送给 LLM 前的最大边长（像素），超过会等比缩放 */
    private static final int MAX_IMAGE_SIDE = 768;
    /** 资源文件夹后缀（如 xxx.pdf.assets 存放 PDF 提取的图片） */
    private static final String ASSET_SUFFIX = ".assets";
    /** 图片标记前缀，用于快速检测片段文本中是否包含图片引用 */
    private static final String IMAGE_MARKER_PREFIX = "[[material-image:";
    /** PDF 页面图片文件名的正则（如 page-1.png、page-1-0.png） */
    private static final String PAGE_IMAGE_RE = "^page-(\\d+)(?:-\\d+)?\\.png$";

    // ========== 摘录与图片处理方法 ==========

    /**
     * 将文本截取为简短摘录（最多 160 字符），去除图片标记。
     * 用于在回答中展示参考资料的简要内容。
     */
    private String excerpt(String text) {
        String cleaned = IMAGE_MARKER_PATTERN.matcher(text == null ? "" : text).replaceAll("");
        cleaned = IMAGE_OCR_PATTERN.matcher(cleaned).replaceAll("图片OCR：");
        String normalized = cleaned.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160) + "...";
    }

    /**
     * 将 ScoredChunk 截取为智能摘录。
     * 如果有高亮关键词，会优先截取包含最多关键词的片段区域（而非简单截取开头），
     * 让用户在摘录中就能看到匹配的部分。
     */
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

    /** 清理摘录文本：去除图片标记和 OCR 标记，规范化空白 */
    private String cleanExcerptText(String text) {
        String cleaned = IMAGE_MARKER_PATTERN.matcher(text == null ? "" : text).replaceAll("");
        cleaned = IMAGE_OCR_PATTERN.matcher(cleaned).replaceAll("");
        return cleaned.trim().replaceAll("\\s+", " ");
    }

    /**
     * 从资料片段中加载嵌入的图片。
     * <p>
     * 处理流程：
     * <ol>
     *   <li>从片段文本中提取图片标记（[[material-image:xxx.png]]）</li>
     *   <li>在资料的资源文件夹中查找对应的图片文件</li>
     *   <li>如果是 PDF 资料且图片不存在，尝试实时渲染 PDF 页面为图片</li>
     *   <li>读取图片 -> 缩放到最大 768px -> 转为 Base64 PNG 格式</li>
     *   <li>如果所有方法都找不到图片，对 PDF 资料尝试渲染当前页的截图</li>
     * </ol>
     *
     * @param material 资料实体（包含存储路径等信息）
     * @param chunk    片段实体（包含文本和页码等信息）
     * @return Base64 编码的图片列表
     */
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

    /** 加载图片文件并转为 Base64 编码的 LlmImage 对象 */
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

    /**
     * 等比缩放图片，确保最长边不超过 maxSide 像素。
     * 使用双线性插值算法保证缩放质量。
     */
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

    // ========== 文件路径处理 ==========

    /** 获取资料的资源文件夹路径（如 xxx.pdf 对应 xxx.pdf.assets/） */
    private Path assetDir(Path sourcePath) {
        return Path.of(sourcePath.toString() + ASSET_SUFFIX).toAbsolutePath().normalize();
    }

    /**
     * 解析资料存储路径：将数据库中存储的路径转为实际文件系统路径。
     * <p>
     * 处理逻辑：
     * <ul>
     *   <li>相对路径：相对于 storageRoot 解析</li>
     *   <li>绝对路径：先尝试重映射到当前存储根目录（兼容部署路径变化）</li>
     *   <li>如果路径在 storageRoot 内部，直接使用</li>
     * </ul>
     */
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

    /**
     * 路径重映射：当部署环境变化导致存储根目录改变时，
     * 尝试从旧的绝对路径中找到存储根目录之后的相对部分，
     * 然后用当前的 storageRoot 拼接出新路径。
     * 这样即使把项目从一个目录搬到另一个目录，资料文件仍能正常访问。
     */
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

    /** 在资源文件夹中查找指定文件名的资源文件，验证路径安全性 */
    private Path resolveAssetPath(Path sourcePath, String fileName) {
        Path dir = assetDir(sourcePath);
        Path direct = dir.resolve(fileName).normalize();
        if (direct.startsWith(dir) && Files.exists(direct) && Files.isRegularFile(direct)) {
            return direct;
        }

        return null;
    }

    /**
     * 将 PDF 的指定页面渲染为 PNG 图片并保存到资源文件夹。
     * 使用 144 DPI 渲染（比默认 72 DPI 更清晰，但文件不会太大）。
     * 渲染结果会被缓存到磁盘，下次访问同一页时直接读取。
     */
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

    /** 将新图片追加到目标列表中，不超过 MAX_IMAGES_PER_REQUEST 限制 */
    private void appendImages(List<LlmImage> target, List<LlmImage> additions) {
        for (LlmImage image : additions) {
            if (target.size() >= MAX_IMAGES_PER_REQUEST) {
                return;
            }
            target.add(image);
        }
    }

    /** 根据文件扩展名检测图片的 MIME 类型 */
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

    /**
     * 从资料的其他片段中补充图片。
     * 遍历已选片段所属资料的所有片段，找到包含图片标记但还未被选中的片段，
     * 加载它们的图片直到达到 MAX_IMAGES_PER_REQUEST 上限。
     */
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

    // ========== 内部枚举和记录类型 ==========

    /**
     * 关键词查询意图枚举。
     * 用于指导关键词匹配时的评分策略。
     */
    private enum KeywordIntent {
        /** 定义类：什么是 XXX */
        DEFINITION,
        /** 功能作用类：XXX 有什么用 */
        FUNCTION,
        /** 对比类：A 和 B 的区别 */
        COMPARISON,
        /** 出现位置类：XXX 在哪里提到 */
        OCCURRENCE
    }

    /** 关键词查询：包含提取的术语列表和识别出的意图类型 */
    private record KeywordQuery(List<String> terms, KeywordIntent intent) {
    }

    /** 资料及其片段的组合，用于批量处理 */
    private record MaterialChunks(LearningMaterialEntity material, List<MaterialChunkEntity> chunks) {
    }

    /** 摘要生成时按章节/标题聚合出的分组 */
    private record SummaryGroup(String title, List<MaterialChunkEntity> chunks) {
    }

    /** 摘要分组构建器 */
    private record SummaryGroupBuilder(String title, List<MaterialChunkEntity> chunks) {
        private SummaryGroupBuilder(String title) {
            this(title, new ArrayList<>());
        }
    }

    /** 解析后的结构化摘要 */
    private record StructuredSummary(String summary, List<SummarySectionResponse> sections) {
    }

    /** BM25 索引缓存条目，包含预构建的 BM25 评分器 */
    private record Bm25IndexCacheEntry(Bm25Scorer scorer) {
    }

    /** 检索结果缓存条目，包含排序后的片段 ID 和分数 */
    private record RetrievalCacheEntry(List<CachedScoredChunk> chunks) {
    }

    /** 超长文档分段计划：目标总字数、拆分段数和每段目标字数。 */
    private record LongDocumentPlan(int targetChars, int parts, int partTargetChars) {
    }

    /** 超长文档续写状态：原始需求、已生成正文、已完成段数和下一段编号。 */
    private record LongDocumentContinuation(
        String originalQuestion,
        String generatedAnswer,
        int completedParts,
        int nextPart
    ) {
    }

    /** 缓存中的片段记录：仅保存 chunkId 和分数（不持有实体引用，避免内存泄漏） */
    private record CachedScoredChunk(Long chunkId, double score, List<String> highlightTerms) {
    }

    /** 关键词覆盖率结果：包含覆盖率分数和缺失的关键词列表 */
    private record TermCoverage(double score, List<String> missingTerms) {
    }

    /**
     * 混合检索候选对象：聚合同一片段在向量检索、BM25 检索和摘要检索中的分数。
     * 用于融合阶段的多路分数合并。
     */
    private static final class HybridCandidate {
        private final LearningMaterialEntity material;
        private final MaterialChunkEntity chunk;
        /** 向量语义检索的最高分数 */
        private double vectorScore;
        /** BM25 关键词检索的最高分数 */
        private double bm25Score;
        /** 摘要种子检索的分数 */
        private double summaryScore;
        /** 高亮关键词列表（用于摘录中重点显示） */
        private List<String> highlightTerms;

        private HybridCandidate(ScoredChunk scoredChunk) {
            this.material = scoredChunk.material();
            this.chunk = scoredChunk.chunk();
            this.highlightTerms = scoredChunk.highlightTerms();
        }
    }

    /**
     * 带分数的片段记录：将资料实体、片段实体和相关性分数绑定在一起。
     * highlightTerms 记录了匹配到的关键词，用于在摘录中高亮显示。
     */
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
