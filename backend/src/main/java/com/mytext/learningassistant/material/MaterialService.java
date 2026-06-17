package com.mytext.learningassistant.material;

/* ======================== 标准库导入 ======================== */
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.net.http.HttpHeaders;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

/* ======================== 第三方库导入 ======================== */
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.admin.UsageRecordEntity;
import com.mytext.learningassistant.admin.UsageRecordRepository;
import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.embedding.EmbeddingClient;
import com.mytext.learningassistant.rag.MaterialSummaryRepository;
import com.mytext.learningassistant.rag.RagQuestionSourceRepository;
import com.mytext.learningassistant.security.OutboundUrlGuard;
import com.mytext.learningassistant.vector.VectorStoreClient;

/* ======================== PDFBox 导入（用于 PDF 解析和渲染） ======================== */
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;

/* ======================== Spring 框架导入 ======================== */
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PreDestroy;

/**
 * 学习资料核心业务服务类。
 *
 * <p>负责学习资料的完整生命周期管理，是整个资料处理模块的核心，包括：
 * <ul>
 *   <li>文件上传（单次上传 / 分片上传 / 网页导入）</li>
 *   <li>文档解析（PDF、Word、PPT、Markdown、纯文本、HTML 等多种格式）</li>
 *   <li>文本分块（语义分块策略：按段落拆分、句子拆分、重叠窗口）</li>
 *   <li>Embedding 向量生成和向量数据库写入</li>
 *   <li>PDF 页面渲染和预览文件管理</li>
 *   <li>OCR 图片文字提取（可选，依赖 Tesseract）</li>
 *   <li>资料的增删改查和重新解析</li>
 * </ul>
 *
 * <h3>关键算法说明</h3>
 * <ul>
 *   <li><b>文本分块</b>：采用语义分块策略，先按段落（双换行）拆分，再按句子（句号、感叹号等标点）拆分，
 *       每个片段 300~800 字符，相邻片段之间有 120 字符的重叠窗口以保持上下文连贯性。
 *       对于包含图片标记的文本，采用特殊策略将图片标记与周围的文本一起作为独立片段。</li>
 *   <li><b>Embedding</b>：通过 OpenAI 兼容接口生成文本向量，使用 SHA-256 作为缓存 key
 *       避免重复请求，缓存上限 2048 条。超限时清空缓存重新积累。</li>
 *   <li><b>PDF 解析</b>：使用 PDFBox 逐页提取文本，对于扫描件（无可抽取文本的页面）
 *       支持可选的 OCR（Tesseract）或保留图片标记供多模态问答使用。大 PDF 可选先压缩。</li>
 *   <li><b>分片上传</b>：持久资料采用分片上传策略，前端将文件切分为固定大小的分片，
 *       逐个上传到服务端临时目录，全部上传完毕后在后台合并、校验、解析。</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>上传会话的后台处理使用独立的 2 线程线程池</li>
 *   <li>PDF 页面渲染使用 Semaphore（许可数 2）限制并发，防止内存溢出</li>
 * </ul>
 *
 * @see MaterialController REST API 控制器
 * @see MaterialFileTicketService 文件下载凭据服务
 */
@Service
public class MaterialService {

    // ======================== 日志和格式化 ========================

    /** SLF4J 日志实例 */
    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);

    /** 日期时间格式化器，用于将 LocalDateTime 转为 "yyyy-MM-dd HH:mm:ss" 格式 */
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 文本分块的最小字符数 */
    private static final int CHUNK_MIN_SIZE = 300;

    /** 文本分块的最大字符数 */
    private static final int CHUNK_MAX_SIZE = 800;

    /** 相邻分块之间的重叠字符数，用于保持上下文连贯 */
    private static final int CHUNK_OVERLAP = 120;

    /** 持久资料默认最大大小：2GB；大 PDF 通过分片上传进入后台解析。 */
    private static final long DEFAULT_MAX_MATERIAL_BYTES = 2L * 1024L * 1024L * 1024L;

    /** 智能问答临时资料默认最大大小：100MB；更大的文件建议先作为持久资料上传。 */
    private static final long DEFAULT_MAX_TEMPORARY_MATERIAL_BYTES = 100L * 1024L * 1024L;

    /** 临时资料返回给前端的预览正文上限；完整正文保存在后端上下文表中。 */
    private static final int TEMPORARY_MATERIAL_RESPONSE_TEXT_CHARS = 20_000;

    /** 网页导入内容默认最大大小：10MB */
    private static final long DEFAULT_MAX_WEB_BYTES = 10L * 1024L * 1024L;

    /** 网页抓取请求的超时时间 */
    private static final Duration WEB_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** 分片上传临时文件目录后缀 */
    private static final String PART_SUFFIX = ".parts";

    /** 资源文件（图片等）目录后缀 */
    private static final String ASSET_SUFFIX = ".assets";

    /** 图片标记前缀，用于在文本中标记图片位置 */
    private static final String IMAGE_MARKER_PREFIX = "[[material-image:";

    /** 图片标记后缀 */
    private static final String IMAGE_MARKER_SUFFIX = "]]";

    /** 预览 PDF 文件后缀 */
    private static final String PREVIEW_SUFFIX = ".preview.pdf";

    /** 页面图片文件名的正则模式，如 "page-3.png" */
    private static final String PAGE_IMAGE_RE = "^page-(\\d+)(?:-\\d+)?\\.png$";

    /** PDF 页面渲染的默认 DPI（每英寸点数） */
    private static final int DEFAULT_RENDER_DPI = 144;

    /** 大 PDF 轻量阅读器页元数据的默认 A4 宽度，避免为取 MediaBox 再加载 300MB PDF。 */
    private static final float DEFAULT_PDF_PAGE_WIDTH = 595.0f;

    /** 大 PDF 轻量阅读器页元数据的默认 A4 高度。 */
    private static final float DEFAULT_PDF_PAGE_HEIGHT = 842.0f;

    /** 内联 PDF OCR 的最大文件大小限制：100MB */
    private static final long DEFAULT_INLINE_PDF_OCR_MAX_BYTES = 100L * 1024L * 1024L;

    /** 内联 PDF OCR 的最大页数限制 */
    private static final int DEFAULT_INLINE_PDF_OCR_MAX_PAGES = 200;

    /** PDF 压缩的最小文件大小阈值：64MB */
    private static final long DEFAULT_PDF_COMPRESSION_MIN_BYTES = 64L * 1024L * 1024L;

    /** PDF 快速导入阈值：默认所有 PDF 都先走稳定的 Poppler 轻量解析，避免 PDFBox/OCR 阻塞上传完成。 */
    private static final long DEFAULT_LARGE_PDF_FAST_IMPORT_BYTES = 128L * 1024L * 1024L;

    /** 大 PDF 快速导入时最多抽取的页数，避免 300MB 级 PDF 全量文本解析拖死上传任务。 */
    private static final int DEFAULT_LARGE_PDF_FAST_IMPORT_MAX_TEXT_PAGES = 80;

    /** 大 PDF 轻量文本抽取的外部命令超时，超时后直接降级为可阅读占位资料。 */
    private static final Duration LARGE_PDF_FAST_IMPORT_TIMEOUT = Duration.ofSeconds(90);

    /** PDF 压缩的目标 DPI */
    private static final int DEFAULT_PDF_COMPRESSION_TARGET_DPI = 144;

    /** 后端文本层缺少真实页面尺寸时使用的默认归一化页面尺寸。 */
    private static final double DEFAULT_TEXT_LAYER_PAGE_SIZE = 1000.0;

    /** 单个文本层块最大保留字符数，避免异常解析结果把整份文档塞进一个 overlay 节点。 */
    private static final int PAGE_TEXT_BLOCK_MAX_LENGTH = 4000;

    /** 图片型大 PDF 后台 OCR 每次处理的页数；小批量能更快回写进度，避免用户长时间看到进度不动。 */
    private static final int OCR_PAGE_BATCH_SIZE = 2;

    /** 上传会话客户端幂等 ID 的最大长度，兼容仍停留在旧迁移状态的数据库字段。 */
    private static final int CLIENT_UPLOAD_ID_MAX_LENGTH = 64;

    /** Embedding 向量缓存的最大条目数 */
    private static final int EMBEDDING_CACHE_MAX_ENTRIES = 2_048;

    /** 关键词提取的正则模式：匹配中文、英文、数字、特殊符号组成的 2 字符以上词组 */
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9+#./-]{2,}");

    /** material_chunk.keywords 字段长度 */
    private static final int CHUNK_KEYWORDS_MAX_LENGTH = 500;

    /** 写入向量库时的批大小，避免超大资料解析时长时间持有全部切片和向量 */
    private static final int VECTOR_UPSERT_BATCH_SIZE = 200;

    /** 关键词停用词列表，这些常见词不会作为关键词输出 */
    private static final List<String> KEYWORD_STOP_WORDS = List.of(
        "the", "and", "for", "with", "that", "this", "from", "into", "are", "was", "were", "has", "have",
        "what", "how", "why", "where", "which", "does", "do", "did", "can", "could", "would", "should",
        "什么", "怎么", "为什么", "哪里", "哪个", "哪些", "一个", "这个", "那个", "以及", "如果", "因为"
    );

    // ======================== 依赖注入的仓库和工具 ========================
    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final MaterialPageRepository materialPageRepository;
    private final MaterialPageTextBlockRepository materialPageTextBlockRepository;
    private final MaterialProcessingJobService materialProcessingJobService;
    private final MaterialSummaryRepository materialSummaryRepository;
    private final RagQuestionSourceRepository ragQuestionSourceRepository;
    private final MaterialUploadSessionRepository materialUploadSessionRepository;
    private final TemporaryMaterialContextRepository temporaryMaterialContextRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final ObjectMapper objectMapper;
    private final EmbeddingClient embeddingClient;
    private final VectorStoreClient vectorStoreClient;
    private final OutboundUrlGuard outboundUrlGuard;
    private final HttpClient httpClient;

    // ======================== 存储和配置 ========================
    /** 文件存储根目录的绝对路径 */
    private final Path storageRoot;
    /** 是否启用 OCR（光学字符识别） */
    private final boolean ocrEnabled;
    /** 测试环境可开启：上传事务提交后立即跑一次资料处理队列，避免集成测试读到 PENDING 中间态。 */
    private final boolean inlineProcessingAfterUpload;
    /** OCR 语言参数（如 "eng+chi_sim" 表示英文+简体中文） */
    private final String ocrLang;
    /** OCR 命令（默认 tesseract） */
    private final String ocrCommand;
    /** OCR 自定义命令模板（支持 {image} 和 {lang} 占位符） */
    private final String ocrCommandTemplate;
    /** OCR 超时时间 */
    private final Duration ocrTimeout;
    /** 是否启用文档格式转换器（LibreOffice/soffice） */
    private final boolean converterEnabled;
    /** 文档转换器命令 */
    private final String converterCommand;
    /** 文档转换器超时时间 */
    private final Duration converterTimeout;
    /** PDF 页面渲染 DPI */
    private final int renderDpi;
    /** 内联 PDF OCR 的最大文件大小 */
    private final long inlinePdfOcrMaxBytes;
    /** 内联 PDF OCR 的最大页数 */
    private final int inlinePdfOcrMaxPages;

    // ======================== PDF 压缩配置 ========================
    /** 是否启用 PDF 压缩 */
    private final boolean pdfCompressionEnabled;
    /** PDF 压缩命令（默认 gs / Ghostscript） */
    private final String pdfCompressionCommand;
    /** PDF 压缩自定义命令模板 */
    private final String pdfCompressionCommandTemplate;
    /** PDF 压缩超时时间 */
    private final Duration pdfCompressionTimeout;
    /** 触发 PDF 压缩的最小文件大小 */
    private final long pdfCompressionMinBytes;
    /** PDF 压缩的目标 DPI */
    private final int pdfCompressionTargetDpi;
    private final long largePdfFastImportBytes;
    private final int largePdfFastImportMaxTextPages;
    private final long maxMaterialBytes;
    private final long maxTemporaryMaterialBytes;
    private final long maxWebBytes;

    // ======================== 线程和缓存 ========================
    /** 上传会话后台处理线程池（2 个线程） */
    private final ExecutorService uploadExecutor = Executors.newFixedThreadPool(2);
    /** 后台向量索引线程池，和上传解析解耦，避免 Embedding 慢导致上传卡住。 */
    private final ExecutorService vectorIndexExecutor = Executors.newFixedThreadPool(2);
    /** 外部命令输出读取线程池，避免 Poppler、LibreOffice、OCR 等命令输出过多时阻塞进程结束。 */
    private final ExecutorService processOutputExecutor = Executors.newCachedThreadPool();
    /** PDF 页面渲染并发信号量（限制同时渲染 2 个页面，防止内存溢出） */
    private final Semaphore renderSemaphore = new Semaphore(2);
    /** Embedding 向量缓存（key 为文本 SHA-256，value 为 JSON 格式的向量） */
    private final ConcurrentMap<String, String> embeddingJsonCache = new ConcurrentHashMap<>();
    /** 已调度的上传解析任务，防止并发分片同时完成时重复提交后台解析。 */
    private final ConcurrentMap<String, Boolean> scheduledProcessingSessions = new ConcurrentHashMap<>();

    /**
     * 构造函数 -- 通过 Spring 依赖注入初始化所有组件和配置。
     * 大量参数通过 {@code @Value} 从 application.properties / application.yml 中读取。
     */
    public MaterialService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        MaterialPageRepository materialPageRepository,
        MaterialPageTextBlockRepository materialPageTextBlockRepository,
        MaterialProcessingJobService materialProcessingJobService,
        MaterialSummaryRepository materialSummaryRepository,
        RagQuestionSourceRepository ragQuestionSourceRepository,
        MaterialUploadSessionRepository materialUploadSessionRepository,
        TemporaryMaterialContextRepository temporaryMaterialContextRepository,
        UsageRecordRepository usageRecordRepository,
        ObjectMapper objectMapper,
        EmbeddingClient embeddingClient,
        VectorStoreClient vectorStoreClient,
        OutboundUrlGuard outboundUrlGuard,
        @Value("${app.storage-dir:${user.dir}/target/learning-assistant-files}") String storageDir,
        @Value("${app.ocr.enabled:false}") boolean ocrEnabled,
        @Value("${app.material-processing.inline-after-upload:false}") boolean inlineProcessingAfterUpload,
        @Value("${app.ocr.lang:eng+chi_sim}") String ocrLang,
        @Value("${app.ocr.command:tesseract}") String ocrCommand,
        @Value("${app.ocr.command-template:}") String ocrCommandTemplate,
        @Value("${app.ocr.timeout:20s}") Duration ocrTimeout,
        @Value("${app.document-preview.converter.enabled:true}") boolean converterEnabled,
        @Value("${app.document-preview.converter.command:soffice}") String converterCommand,
        @Value("${app.document-preview.converter.timeout:60s}") Duration converterTimeout,
        @Value("${app.document-preview.render-dpi:144}") int renderDpi,
        @Value("${app.ocr.inline-pdf-max-bytes:104857600}") long inlinePdfOcrMaxBytes,
        @Value("${app.ocr.inline-pdf-max-pages:200}") int inlinePdfOcrMaxPages,
        @Value("${app.pdf.compression.enabled:true}") boolean pdfCompressionEnabled,
        @Value("${app.pdf.compression.command:gs}") String pdfCompressionCommand,
        @Value("${app.pdf.compression.command-template:}") String pdfCompressionCommandTemplate,
        @Value("${app.pdf.compression.timeout:180s}") Duration pdfCompressionTimeout,
        @Value("${app.pdf.compression.min-bytes:67108864}") long pdfCompressionMinBytes,
        @Value("${app.pdf.compression.target-dpi:144}") int pdfCompressionTargetDpi,
        @Value("${app.pdf.large-fast-import-bytes:134217728}") long largePdfFastImportBytes,
        @Value("${app.pdf.large-fast-import-max-text-pages:80}") int largePdfFastImportMaxTextPages,
        @Value("${app.material.max-file-bytes:2147483648}") long maxMaterialBytes,
        @Value("${app.material.max-temporary-file-bytes:104857600}") long maxTemporaryMaterialBytes,
        @Value("${app.material.max-web-bytes:10485760}") long maxWebBytes
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.materialPageRepository = materialPageRepository;
        this.materialPageTextBlockRepository = materialPageTextBlockRepository;
        this.materialProcessingJobService = materialProcessingJobService;
        this.materialSummaryRepository = materialSummaryRepository;
        this.ragQuestionSourceRepository = ragQuestionSourceRepository;
        this.materialUploadSessionRepository = materialUploadSessionRepository;
        this.temporaryMaterialContextRepository = temporaryMaterialContextRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.objectMapper = objectMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStoreClient = vectorStoreClient;
        this.outboundUrlGuard = outboundUrlGuard;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
        this.ocrEnabled = ocrEnabled;
        this.inlineProcessingAfterUpload = inlineProcessingAfterUpload;
        this.ocrLang = ocrLang == null || ocrLang.isBlank() ? "eng+chi_sim" : ocrLang.trim();
        this.ocrCommand = ocrCommand == null || ocrCommand.isBlank() ? "tesseract" : ocrCommand.trim();
        this.ocrCommandTemplate = normalizeOptionalText(ocrCommandTemplate);
        this.ocrTimeout = ocrTimeout == null ? Duration.ofSeconds(20) : ocrTimeout;
        this.converterEnabled = converterEnabled;
        this.converterCommand = resolveConverterCommand(converterCommand);
        this.converterTimeout = converterTimeout == null ? Duration.ofSeconds(60) : converterTimeout;
        this.renderDpi = renderDpi <= 0 ? DEFAULT_RENDER_DPI : renderDpi;
        this.inlinePdfOcrMaxBytes = inlinePdfOcrMaxBytes <= 0 ? DEFAULT_INLINE_PDF_OCR_MAX_BYTES : inlinePdfOcrMaxBytes;
        this.inlinePdfOcrMaxPages = inlinePdfOcrMaxPages <= 0 ? DEFAULT_INLINE_PDF_OCR_MAX_PAGES : inlinePdfOcrMaxPages;
        this.pdfCompressionEnabled = pdfCompressionEnabled;
        this.pdfCompressionCommand = pdfCompressionCommand == null || pdfCompressionCommand.isBlank() ? "gs" : pdfCompressionCommand.trim();
        this.pdfCompressionCommandTemplate = normalizeOptionalText(pdfCompressionCommandTemplate);
        this.pdfCompressionTimeout = pdfCompressionTimeout == null ? Duration.ofSeconds(180) : pdfCompressionTimeout;
        this.pdfCompressionMinBytes = pdfCompressionMinBytes <= 0 ? DEFAULT_PDF_COMPRESSION_MIN_BYTES : pdfCompressionMinBytes;
        this.pdfCompressionTargetDpi = pdfCompressionTargetDpi <= 0 ? DEFAULT_PDF_COMPRESSION_TARGET_DPI : pdfCompressionTargetDpi;
        this.largePdfFastImportBytes = largePdfFastImportBytes <= 0 ? DEFAULT_LARGE_PDF_FAST_IMPORT_BYTES : largePdfFastImportBytes;
        this.largePdfFastImportMaxTextPages = largePdfFastImportMaxTextPages <= 0
            ? DEFAULT_LARGE_PDF_FAST_IMPORT_MAX_TEXT_PAGES
            : largePdfFastImportMaxTextPages;
        this.maxMaterialBytes = maxMaterialBytes <= 0 ? DEFAULT_MAX_MATERIAL_BYTES : maxMaterialBytes;
        this.maxTemporaryMaterialBytes = maxTemporaryMaterialBytes <= 0 ? DEFAULT_MAX_TEMPORARY_MATERIAL_BYTES : maxTemporaryMaterialBytes;
        this.maxWebBytes = maxWebBytes <= 0 ? DEFAULT_MAX_WEB_BYTES : maxWebBytes;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(WEB_REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * 应用关闭时清理线程池资源。
     */
    @PreDestroy
    public void shutdown() {
        uploadExecutor.shutdownNow();
        vectorIndexExecutor.shutdownNow();
        processOutputExecutor.shutdownNow();
    }

    /**
     * worker 执行数据库任务的入口。
     *
     * <p>任务队列表负责重试、锁和可观测；实际解析能力继续复用本服务中已经稳定的
     * PDF/Word/TXT/HTML 解析、预览、切片和向量写入逻辑，避免为异步化重写一套解析器。</p>
     */
    @Transactional(noRollbackFor = BusinessException.class, propagation = Propagation.NOT_SUPPORTED)
    void executeProcessingJob(MaterialProcessingJobEntity job) {
        if (job == null || job.getMaterialId() == null || job.getJobType() == null) {
            return;
        }
        switch (job.getJobType()) {
            case EXTRACT_TEXT_FAST -> runTextExtractionPipeline(job.getMaterialId(), job.getId());
            case OCR_PAGE_BATCH -> runOcrPageBatchPipeline(job.getMaterialId(), job.getId());
            case EXTRACT_TEXT_REMAINING -> runRemainingTextExtractionPipeline(job.getMaterialId(), job.getId());
            case BUILD_PREVIEW -> runPreviewPipeline(job.getMaterialId());
            case CHUNK_TEXT, BUILD_BM25 -> markAlreadyHandledPipelineStep(job.getMaterialId(), job.getJobType());
            case BUILD_EMBEDDING, SYNC_VECTOR_STORE -> runEmbeddingPipeline(job.getMaterialId(), job.getJobType());
        }
    }

    /**
     * 任务最终失败后同步资料状态，避免资料卡片一直停留在运行中。
     */
    @Transactional
    void markProcessingJobFailed(MaterialProcessingJobEntity job) {
        if (job == null || job.getMaterialId() == null) {
            return;
        }
        learningMaterialRepository.findById(job.getMaterialId()).ifPresent(material -> {
            if (job.getJobType() == MaterialProcessingJobType.BUILD_EMBEDDING
                || job.getJobType() == MaterialProcessingJobType.SYNC_VECTOR_STORE) {
                material.setIndexStatus(MaterialIndexStatus.READY);
                material.setProcessingProgressPercent(100);
                material.setProcessingStage("处理完成");
                material.setProcessingMessage("文本、预览和 BM25 已可用；向量增强失败，可在任务面板重试，不影响阅读和问答");
            } else if (job.getJobType() == MaterialProcessingJobType.EXTRACT_TEXT_REMAINING) {
                material.setTextStatus(MaterialTextStatus.PARTIAL);
                material.setIndexStatus(MaterialIndexStatus.PARTIAL);
                material.setProcessingProgressPercent(Math.max(85, nullToZero(material.getProcessingProgressPercent())));
                material.setProcessingStage("部分页面可用");
                material.setProcessingMessage(remainingTextFailureMessage(material));
            } else if (job.getJobType() == MaterialProcessingJobType.OCR_PAGE_BATCH) {
                // OCR 只是图片型 PDF 的增强步骤；失败时不能把已可预览的资料整体标成解析失败。
                material.setTextStatus(MaterialTextStatus.PARTIAL);
                material.setIndexStatus(MaterialIndexStatus.PARTIAL);
                material.setOcrStatus(MaterialOcrStatus.FAILED);
                material.setProcessingProgressPercent(100);
                material.setProcessingStage("图片页已入库");
                material.setProcessingMessage("PDF 页面已按页入库，但 OCR 后台识别失败；请检查 Tesseract/语言包配置后在任务面板重试");
            } else {
                material.setTextStatus(MaterialTextStatus.FAILED);
                material.setIndexStatus(MaterialIndexStatus.FAILED);
                material.setProcessingStage("处理失败");
                material.setProcessingMessage(job.getErrorMessage());
            }
            learningMaterialRepository.save(material);
        });
    }

    private String remainingTextFailureMessage(LearningMaterialEntity material) {
        int textPages = material == null || material.getTextPageCount() == null ? 0 : material.getTextPageCount();
        int totalPages = material == null || material.getPageCount() == null ? 0 : material.getPageCount();
        if (totalPages > 0 && textPages > 0 && textPages < totalPages) {
            return "已保留前 " + textPages + "/" + totalPages + " 页可用片段，剩余页面补齐失败，可重新解析重试";
        }
        return "已保留当前可用片段，剩余页面补齐失败，可重新解析重试";
    }

    /**
     * 上传学习资料文件（单次上传模式）。
     *
     * <p>处理流程：
     * <ol>
     *   <li>校验文件非空且大小不超限</li>
     *   <li>推断文件类型（根据扩展名或显式指定）</li>
     *   <li>将文件保存到磁盘</li>
     *   <li>解析文件提取文本（根据类型调用不同解析器）</li>
     *   <li>将文本切分为知识片段（Chunk）</li>
     *   <li>为每个片段生成 Embedding 向量</li>
     *   <li>将数据保存到数据库和向量数据库</li>
     * </ol>
     *
     * @param ownerId         资料所有者用户 ID
     * @param title           资料标题（可选）
     * @param sourceTypeValue 来源类型字符串（可选，为空时自动推断）
     * @param file            上传的文件
     * @param sourceUrl       来源 URL（可选）
     * @return 保存成功的资料响应
     * @throws BusinessException 文件为空、过大或解析失败时抛出
     */
    @Transactional
    public MaterialResponse upload(long ownerId, String title, String sourceTypeValue, MultipartFile file, String sourceUrl) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "file cannot be empty");
        }
        if (file.getSize() > maxMaterialBytes) {
            throw new BusinessException(400, "file is too large");
        }
        MaterialSourceType sourceType = parseSourceType(sourceTypeValue, file.getOriginalFilename());
        String originalName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        rejectUnsupportedLegacyOfficeFile(originalName);
        Path storagePath = resolveStoragePath(ownerId, originalName);
        try {
            Files.createDirectories(storagePath.getParent());
            file.transferTo(storagePath);
        } catch (IOException exception) {
            throw new BusinessException(500, "failed to save file");
        }
        return createQueuedMaterial(ownerId, title, sourceType, originalName, storagePath, sourceUrl, file.getSize());
    }

    /**
     * 解析临时资料文件，只返回提取文本，不创建资料记录。
     */
    public TemporaryMaterialResponse parseTemporary(long ownerId, String title, String sourceTypeValue, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "file cannot be empty");
        }
        if (file.getSize() > maxTemporaryMaterialBytes) {
            throw new BusinessException(400, "智能问答临时资料最大支持 100MB；大文件请切换到资料问答上传，系统会在后台解析并显示进度。");
        }
        MaterialSourceType sourceType = parseSourceType(sourceTypeValue, file.getOriginalFilename());
        String originalName = file.getOriginalFilename() == null ? "temporary-material" : file.getOriginalFilename();
        rejectUnsupportedLegacyOfficeFile(originalName);
        Path storagePath = resolveTemporaryStoragePath(ownerId, originalName);
        try {
            Files.createDirectories(storagePath.getParent());
            file.transferTo(storagePath);
            ParsedMaterial parsed = parseMaterial(sourceType, storagePath);
            String text = temporaryText(parsed);
            if (text.isBlank()) {
                throw new BusinessException(400, "未能从资料中提取到可问答的文本");
            }
            String normalizedTitle = normalizeText(title, originalName);
            String contextId = UUID.randomUUID().toString();
            TemporaryMaterialContextEntity context = new TemporaryMaterialContextEntity();
            context.setId(contextId);
            context.setOwnerId(ownerId);
            context.setTitle(normalizedTitle);
            context.setOriginalName(originalName);
            context.setSourceType(sourceType.name());
            context.setText(text);
            context.setExcerpt(excerpt(text));
            context.setFileSize(file.getSize());
            temporaryMaterialContextRepository.save(context);
            return new TemporaryMaterialResponse(
                contextId,
                normalizedTitle,
                originalName,
                sourceType.name(),
                temporaryResponseText(text),
                context.getExcerpt(),
                file.getSize(),
                true
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            log.warn("Temporary material parsing failed for {}", originalName, exception);
            throw new BusinessException(400, "临时资料解析失败");
        } finally {
            deleteStoredAssets(storagePath.toString());
            deletePreviewFile(storagePath.toString());
            deleteStoredFile(storagePath.toString());
        }
    }

    /**
     * 生成返回给前端的临时资料预览文本。
     *
     * <p>完整正文已经写入 temporary_material_context；这里限制长度，避免聊天草稿和请求体撑爆浏览器本地存储。</p>
     */
    private String temporaryResponseText(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= TEMPORARY_MATERIAL_RESPONSE_TEXT_CHARS) {
            return text;
        }
        return text.substring(0, TEMPORARY_MATERIAL_RESPONSE_TEXT_CHARS)
            + "\n\n[内容过长，完整内容已保存在后端临时上下文中，后续提问会继续引用全文。]";
    }

    /**
     * 通过 URL 导入网页内容作为学习资料。
     *
     * <p>处理流程：
     * <ol>
     *   <li>校验并解析 URL</li>
     *   <li>发送 HTTP GET 请求获取网页内容</li>
     *   <li>清理 HTML 标签，提取纯文本</li>
     *   <li>保存文本到磁盘并执行分块和索引</li>
     * </ol>
     *
     * @param ownerId   资料所有者用户 ID
     * @param title     资料标题（可选，为空时从 URL 推断）
     * @param sourceUrl 网页 URL
     * @return 导入成功的资料响应
     */
    @Transactional
    public MaterialResponse importWeb(long ownerId, String title, String sourceUrl) {
        URI uri = parseWebUri(sourceUrl);
        FetchedWebResource resource = fetchWebResource(uri);
        String originalName = inferWebOriginalName(uri, resource);
        MaterialSourceType sourceType = inferWebSourceType(resource, originalName);
        originalName = ensureWebExtension(originalName, sourceType);
        boolean htmlPage = sourceType == MaterialSourceType.HTML || sourceType == MaterialSourceType.WEB;
        String content = null;
        if (htmlPage) {
            content = cleanWebText(resource.textBody());
            if (content.isBlank()) {
                throw new BusinessException(400, "web page content cannot be empty");
            }
        }
        Path storagePath = resolveStoragePath(ownerId, originalName);
        try {
            Files.createDirectories(storagePath.getParent());
            if (htmlPage) {
                Files.writeString(storagePath, content, StandardCharsets.UTF_8);
            } else {
                Files.write(storagePath, resource.body());
            }
        } catch (IOException exception) {
            throw new BusinessException(500, "failed to save web material");
        }
        long fileSize = htmlPage ? content.getBytes(StandardCharsets.UTF_8).length : resource.body().length;
        return createQueuedMaterial(ownerId, title, htmlPage ? MaterialSourceType.WEB : sourceType, originalName, storagePath, uri.toString(), fileSize);
    }

    /**
     * 创建分片上传会话。
     *
     * <p>前端大文件上传的入口，创建会话后可通过 uploadChunk 方法逐个上传分片。
     * 同一个 clientUploadId 的重复请求具有幂等性（返回已有会话）。
     *
     * @param ownerId  资料所有者用户 ID
     * @param request  创建请求（包含文件元数据）
     * @return 上传会话信息
     */
    @Transactional
    public MaterialUploadSessionResponse createUploadSession(long ownerId, MaterialUploadSessionCreateRequest request) {
        String clientUploadId = normalizeClientUploadId(request.clientUploadId());
        String title = normalizeText(request.title(), "Untitled material");
        String originalName = normalizeText(request.originalName(), "unknown");
        MaterialSourceType sourceType = parseSourceType(request.sourceType(), originalName);
        String sourceUrl = normalizeOptionalText(request.sourceUrl());
        long fileSize = request.fileSize();
        int chunkSize = request.chunkSize();
        validateMaterialFileSize(fileSize);
        int totalChunks = calculateTotalChunks(fileSize, chunkSize);

        MaterialUploadSessionEntity existing = materialUploadSessionRepository
            .findByOwnerIdAndClientUploadId(ownerId, clientUploadId)
            .orElse(null);
        if (existing != null) {
            boolean recreateSession = false;
            if (isOrphanedUploadSession(existing)) {
                discardUploadSession(existing);
                recreateSession = true;
            }
            if (!recreateSession && !sameUploadSessionMetadata(existing, title, originalName, sourceType, sourceUrl, fileSize, chunkSize, totalChunks)) {
                if (isDiscardableUploadSession(existing)) {
                    discardUploadSession(existing);
                    recreateSession = true;
                } else {
                    throw new BusinessException(409, "Upload metadata mismatch");
                }
            }
            if (!recreateSession && existing.getStatus() == MaterialUploadSessionStatus.FAILED) {
                discardUploadSession(existing);
                recreateSession = true;
            }
            if (!recreateSession) {
                return toUploadSessionResponse(existing);
            }
        }

        LearningMaterialEntity material = new LearningMaterialEntity();
        material.setOwnerId(ownerId);
        material.setTitle(title);
        material.setSourceType(sourceType);
        material.setOriginalName(originalName);
        material.setStoragePath(storagePathValue(resolveStoragePath(ownerId, originalName)));
        material.setSourceUrl(sourceUrl);
        material.setFileSize(fileSize);
        material.setParseStatus(MaterialParseStatus.PENDING);
        material.setParseProgressPercent(0);
        material.setParseStage("等待上传");
        material.setParseMessage("文件上传完成后开始后台解析");
        material.setUploadStatus(MaterialUploadStatus.UPLOADING);
        material.setTextStatus(MaterialTextStatus.PENDING);
        material.setIndexStatus(MaterialIndexStatus.PENDING);
        material.setOcrStatus(ocrEnabled ? MaterialOcrStatus.PENDING : MaterialOcrStatus.DISABLED);
        material.setProcessingProgressPercent(0);
        material.setProcessingStage("等待上传");
        material.setProcessingMessage("文件上传完成后开始后台解析");
        material.setIndexedChunkCount(0);
        material.setTextPageCount(0);
        material.setSummaryStatus(MaterialSummaryStatus.PENDING);
        material.setChunkCount(0);
        LearningMaterialEntity savedMaterial = learningMaterialRepository.save(material);

        MaterialUploadSessionEntity session = new MaterialUploadSessionEntity();
        session.setSessionId(UUID.randomUUID().toString());
        session.setClientUploadId(clientUploadId);
        session.setOwnerId(ownerId);
        session.setTitle(title);
        session.setSourceType(sourceType);
        session.setOriginalName(originalName);
        session.setSourceUrl(sourceUrl);
        session.setFileSize(fileSize);
        session.setChunkSize(chunkSize);
        session.setTotalChunks(totalChunks);
        session.setUploadedChunks(0);
        session.setStoragePath(savedMaterial.getStoragePath());
        session.setChecksumSha256(normalizeOptionalText(request.checksumSha256()));
        session.setStatus(MaterialUploadSessionStatus.UPLOADING);
        session.setMaterialId(savedMaterial.getId());
        materialUploadSessionRepository.save(session);
        recordMaterialLog(ownerId, "CREATE_UPLOAD_SESSION", savedMaterial.getId(), savedMaterial.getTitle(), savedMaterial.getOriginalName(), savedMaterial.getFileSize());
        ensureUploadPartsDir(session);
        return toUploadSessionResponse(session);
    }

    /**
     * 查询上传会话的当前状态。
     *
     * @param ownerId   用户 ID
     * @param sessionId 会话 ID
     * @return 上传会话状态信息
     */
    @Transactional(readOnly = true)
    public MaterialUploadSessionResponse getUploadSession(long ownerId, String sessionId) {
        return toUploadSessionResponse(requireUploadSession(ownerId, sessionId));
    }

    /**
     * 上传一个文件分片。
     *
     * <p>当所有分片都已上传完毕后，自动触发后台合并和解析任务。
     * 分片支持 SHA-256 校验，已有分片的重复上传是幂等的。
     *
     * @param ownerId       用户 ID
     * @param sessionId     会话 ID
     * @param chunkIndex    分片索引（从 0 开始）
     * @param totalChunks   总分片数
     * @param chunk         分片文件内容
     * @param checksumSha256 分片校验值（可选）
     * @return 上传会话的最新状态
     */
    @Transactional
    public MaterialUploadSessionResponse uploadChunk(
        long ownerId,
        String sessionId,
        int chunkIndex,
        int totalChunks,
        MultipartFile chunk,
        String checksumSha256
    ) {
        if (chunk == null || chunk.isEmpty()) {
            throw new BusinessException(400, "chunk cannot be empty");
        }
        MaterialUploadSessionEntity session = requireUploadSession(ownerId, sessionId);
        if (session.getStatus() == MaterialUploadSessionStatus.SUCCESS) {
            return toUploadSessionResponse(session);
        }
        if (session.getStatus() == MaterialUploadSessionStatus.FAILED) {
            throw new BusinessException(409, "Upload session failed");
        }
        if (session.getTotalChunks() != totalChunks) {
            throw new BusinessException(400, "Chunk total mismatch");
        }
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new BusinessException(400, "Invalid chunk index");
        }
        long expectedChunkSize = expectedPartSize(session, chunkIndex);
        if (chunk.getSize() != expectedChunkSize) {
            throw new BusinessException(400, "Chunk size mismatch");
        }
        Path partPath = partPath(session, chunkIndex);
        Path tempPartPath = partPath.resolveSibling(partPath.getFileName() + ".uploading-" + UUID.randomUUID());
        try {
            Files.createDirectories(partPath.getParent());
            chunk.transferTo(tempPartPath);
            String expectedChecksum = normalizeOptionalText(checksumSha256);
            // 单个分片允许独立校验，尽早发现前端重传或网络传输造成的内容损坏。
            if (expectedChecksum != null && !expectedChecksum.isBlank()) {
                String actualChecksum = sha256(tempPartPath);
                if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                    throw new BusinessException(400, "chunk checksum mismatch");
                }
            }
            if (Files.exists(partPath)) {
                // 相同分片重复上传视为幂等成功，内容不同则拒绝覆盖，避免合并出混合版本文件。
                if (Files.size(partPath) == Files.size(tempPartPath) && sameFileHash(partPath, tempPartPath)) {
                    refreshSessionProgress(session);
                    return toUploadSessionResponse(session);
                }
                throw new BusinessException(409, "Chunk content mismatch");
            }
            moveUploadedPart(tempPartPath, partPath);
        } catch (IOException exception) {
            throw new BusinessException(500, "failed to save chunk");
        } finally {
            try {
                Files.deleteIfExists(tempPartPath);
            } catch (IOException ignored) {
                // 临时分片清理失败不影响上传主流程；后续同名正式分片不会被它覆盖。
            }
        }

        refreshSessionProgress(session);
        if (session.getTotalChunks() != null
            && hasAllUploadedParts(session)
            && session.getStatus() == MaterialUploadSessionStatus.UPLOADING) {
            // 只有第一次观察到全部分片齐全时才切换到处理态，避免并发请求重复调度后台合并。
            session.setStatus(MaterialUploadSessionStatus.PROCESSING);
            materialUploadSessionRepository.save(session);
            markMaterialParsing(session);
            scheduleProcessing(session.getSessionId());
        }
        return toUploadSessionResponse(session);
    }

    /**
     * 获取当前用户的学习资料列表，按创建时间降序排列。
     *
     * @param ownerId 资料所有者用户 ID
     * @return 资料列表（每个元素包含基本元数据和解析状态）
     */
    public List<MaterialResponse> list(long ownerId) {
        return learningMaterialRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    /**
     * 获取单个学习资料的详细信息。
     *
     * @param ownerId    资料所有者用户 ID
     * @param materialId 资料 ID
     * @return 资料详情（含解析状态、进度、分块数、预览状态等）
     * @throws BusinessException 资料不存在或不属于当前用户时抛出 404
     */
    @Transactional(readOnly = true)
    public MaterialDetailResponse detail(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        return toDetailResponse(material);
    }

    @Transactional(readOnly = true)
    public List<MaterialProcessingJobResponse> jobs(long ownerId, long materialId) {
        return materialProcessingJobService.jobs(ownerId, materialId);
    }

    @Transactional
    public MaterialProcessingJobResponse retryJob(long ownerId, long materialId, long jobId) {
        return materialProcessingJobService.retry(ownerId, materialId, jobId);
    }

    /**
     * 获取指定资料的所有知识片段列表。
     *
     * @param ownerId    资料所有者用户 ID
     * @param materialId 资料 ID
     * @return 知识片段列表（按 chunkIndex 升序排列）
     * @throws BusinessException 资料不存在时抛出 404
     */
    @Transactional(readOnly = true)
    public List<MaterialChunkResponse> chunks(long ownerId, long materialId) {
        learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        return materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId)
            .stream()
            .map(this::toChunkResponse)
            .toList();
    }

    /**
     * 获取指定资料的页面信息列表（仅支持 PDF 类型资料）。
     *
     * <p>遍历预览 PDF 的每一页，返回页面尺寸、图片文件名、关联的知识片段 ID 和渲染状态。
     * 对于非 PDF 类型的资料，返回空列表。
     *
     * @param ownerId    资料所有者用户 ID
     * @param materialId 资料 ID
     * @return 页面信息列表；无预览 PDF 或非 PDF 资料时返回空列表
     */
    @Transactional(readOnly = true)
    public List<MaterialPageResponse> pages(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        Path previewPath = previewPdfPath(material);
        if (previewPath == null || !Files.exists(previewPath) || !Files.isRegularFile(previewPath)) {
            return List.of();
        }
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        if (isLargePdfFastImport(sourcePath) && material.getPageCount() != null && material.getPageCount() > 0) {
            return lightweightPdfPages(material);
        }
        try (var document = Loader.loadPDF(previewPath.toFile())) {
            int pageCount = document.getNumberOfPages();
            Map<Integer, List<Long>> pageChunks = chunksByPage(materialId, pageCount);
            List<MaterialPageResponse> pages = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                int pageNo = pageIndex + 1;
                PDRectangle mediaBox = document.getPage(pageIndex).getMediaBox();
                String imageName = pageImageName(pageNo);
                pages.add(new MaterialPageResponse(
                    pageNo,
                    mediaBox == null ? null : mediaBox.getWidth(),
                    mediaBox == null ? null : mediaBox.getHeight(),
                    imageName,
                    pageChunks.getOrDefault(pageNo, List.of()),
                    isPageRendered(resolveStoredPath(material.getStoragePath()), imageName) ? "READY" : "PENDING"
                ));
            }
            return pages;
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * 获取学习资料的原始文件信息（用于文件下载/预览）。
     *
     * @param ownerId    资料所有者用户 ID
     * @param materialId 资料 ID
     * @return 文件资源信息（包含路径、文件名、Content-Type、文件大小）
     * @throws BusinessException 资料或文件不存在时抛出 404
     */
    @Transactional(readOnly = true)
    public MaterialFileResource file(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        Path path = resolveStoredPath(material.getStoragePath());
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BusinessException(404, "Material file not found");
        }
        try {
            return new MaterialFileResource(
                path,
                material.getOriginalName(),
                contentTypeFor(material.getSourceType(), material.getOriginalName(), path),
                Files.size(path)
            );
        } catch (IOException exception) {
            throw new BusinessException(500, "failed to read material file");
        }
    }

    /**
     * 获取学习资料的预览 PDF 文件。
     *
     * <p>对于 PDF 资料：优先返回压缩后的预览 PDF，不存在则返回原始 PDF。
     * 对于 Word/DOCX 资料：返回通过 LibreOffice 转换生成的预览 PDF。
     * 如果预览 PDF 不存在，会尝试重新转换生成。
     *
     * @param ownerId    资料所有者用户 ID
     * @param materialId 资料 ID
     * @return 预览 PDF 文件资源信息
     * @throws BusinessException 预览文件不存在且无法重新生成时抛出 404
     */
    @Transactional(readOnly = true)
    public MaterialFileResource previewFile(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        Path path = previewPdfPath(material);
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            path = regenerateOfficePreviewIfPossible(material);
        }
        if (path == null || !Files.exists(path) || !Files.isRegularFile(path)) {
            throw new BusinessException(404, "Word 预览 PDF 不存在，已尝试重新转换但失败。请确认已安装 LibreOffice/soffice 后重新解析该资料。");
        }
        String baseName = stripExtension(material.getOriginalName());
        if (baseName.isBlank()) {
            baseName = "material-" + materialId;
        }
        try {
            return new MaterialFileResource(
                path,
                baseName + ".pdf",
                "application/pdf",
                Files.size(path)
            );
        } catch (IOException exception) {
            throw new BusinessException(500, "failed to read material preview file");
        }
    }

    /**
     * 重新生成 Word 预览 PDF（当预览文件缺失时的兜底方案）。
     *
     * <p>仅对 DOCX/WORD 类型有效，通过 LibreOffice 转换生成预览 PDF。
     * 对于其他类型或转换失败的情况返回 null。
     *
     * @param material 资料实体
     * @return 预览 PDF 路径；转换失败或非 Word 类型时返回 null
     */
    private Path regenerateOfficePreviewIfPossible(LearningMaterialEntity material) {
        MaterialSourceType sourceType = material.getSourceType();
        if (sourceType != MaterialSourceType.DOCX && sourceType != MaterialSourceType.WORD) {
            return null;
        }
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
            return null;
        }
        return convertOfficeToPdf(sourcePath);
    }

    /**
     * 获取资料中的图片资源。
     *
     * <p>支持两种图片来源：
     * <ul>
     *   <li><b>已提取的资源图片</b>：从 Word/PPT 中提取的嵌入图片</li>
     *   <li><b>PDF 页面渲染图</b>：按需渲染 PDF 指定页面为 PNG 图片（使用 Semaphore 限制并发）</li>
     * </ul>
     *
     * @param ownerId    资料所有者用户 ID
     * @param materialId 资料 ID
     * @param fileName   图片文件名（如 "page-3.png"、"slide-1-001.png"）
     * @return 图片文件资源信息（包含路径、文件名、Content-Type、文件大小）
     * @throws BusinessException 图片不存在或文件名无效时抛出异常
     */
    @Transactional(readOnly = true)
    public MaterialFileResource image(long ownerId, long materialId, String fileName) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        String safeName = sanitizeAssetFileName(fileName);
        if (safeName == null) {
            throw new BusinessException(400, "Invalid image name");
        }
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        Path path = resolveAssetPath(sourcePath, safeName);
        if (path == null && isPageImageName(safeName)) {
            Path previewPath = previewPdfPath(material);
            if (previewPath == null || !Files.exists(previewPath) || !Files.isRegularFile(previewPath)) {
                log.warn(
                    "Preview source file is missing; cannot render image. materialId={}, ownerId={}, sourcePath={}",
                    materialId,
                    ownerId,
                    sourcePath
                );
                throw new BusinessException(404, "预览文件缺失，请重新解析或重新上传该资料");
            }
            path = renderPdfPageAsset(sourcePath, previewPath, safeName);
        }
        if (path == null) {
            log.warn(
                "Material image is unavailable after lazy render. materialId={}, ownerId={}, fileName={}, sourcePath={}",
                materialId,
                ownerId,
                safeName,
                sourcePath
            );
            throw new BusinessException(404, "资料图片不存在，请重新解析或重新上传该资料");
        }
        try {
            return new MaterialFileResource(
                path,
                safeName,
                imageContentType(safeName),
                Files.size(path)
            );
        } catch (IOException exception) {
            throw new BusinessException(500, "failed to read material image");
        }
    }

    /**
     * 更新学习资料的标题或来源 URL。
     *
     * <p>仅更新请求中非空的字段，不影响其他字段和已有的解析结果、知识片段和向量索引。
     *
     * @param ownerId    资料所有者用户 ID
     * @param materialId 资料 ID
     * @param request    更新请求（title 和 sourceUrl 字段可选）
     * @return 更新后的资料详情
     * @throws BusinessException 资料不存在时抛出 404；标题为空白时抛出 400
     */
    @Transactional
    public MaterialDetailResponse update(long ownerId, long materialId, UpdateMaterialRequest request) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        String title = request == null ? null : request.title();
        if (title != null) {
            String normalizedTitle = title.trim();
            if (normalizedTitle.isBlank()) {
                throw new BusinessException(400, "material title cannot be blank");
            }
            material.setTitle(normalizedTitle);
        }
        String sourceUrl = request == null ? null : request.sourceUrl();
        if (sourceUrl != null) {
            material.setSourceUrl(sourceUrl.trim().isBlank() ? null : sourceUrl.trim());
        }
        return toDetailResponse(learningMaterialRepository.save(material));
    }

    /**
     * 重新解析学习资料。
     *
     * <p>完整处理流程：
     * <ol>
     *   <li>校验资料和原文件是否存在</li>
     *   <li>更新解析状态为"解析中"</li>
     *   <li>重新提取文本（根据文件类型调用对应解析器）</li>
     *   <li>删除旧的知识片段、向量索引、摘要和问答来源关联</li>
     *   <li>重新切分文本、生成 Embedding 和知识片段</li>
     *   <li>生成预览元数据（PDF 页面数等）</li>
     *   <li>更新解析状态为"完成"</li>
     * </ol>
     *
     * @param ownerId    资料所有者用户 ID
     * @param materialId 资料 ID
     * @return 重新解析后的资料详情
     * @throws BusinessException 资料/文件不存在时抛出 404；解析失败时抛出 400
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public MaterialDetailResponse reparse(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
            throw new BusinessException(404, "Original material file is missing. Please upload this material again.");
        }

        material.setParseStatus(MaterialParseStatus.PARSING);
        material.setParseProgressPercent(10);
        material.setParseStage("读取原文件");
        material.setParseMessage("正在准备重新解析");
        learningMaterialRepository.saveAndFlush(material);

        ParsedMaterial parsed = parseMaterial(material.getSourceType(), sourcePath, (percent, stage, message) ->
            updateMaterialParseProgress(
                material.getId(),
                material.getOwnerId(),
                Math.min(44, Math.max(12, 12 + (percent - 30) * 32 / 20)),
                stage,
                message
            ));
        if (parsed.isBlank()) {
            material.setParseStatus(MaterialParseStatus.FAILED);
            material.setParseStage("解析失败");
            material.setParseMessage("未提取到有效文本");
            learningMaterialRepository.save(material);
            throw new BusinessException(400, "material parsing failed");
        }

        updateMaterialParseProgress(material.getId(), material.getOwnerId(), 45, "切分文本", "正在按段落和页码生成知识片段");
        List<ChunkDraft> chunks = chunkMaterial(parsed);
        updateMaterialParseProgress(material.getId(), material.getOwnerId(), 65, "重建索引", "正在删除旧索引并写入新片段");
        materialSummaryRepository.deleteByMaterialIdAndUserId(materialId, ownerId);
        ragQuestionSourceRepository.deleteByMaterialId(materialId);
        vectorStoreClient.deleteMaterial(ownerId, materialId);
        materialPageTextBlockRepository.deleteByMaterialId(materialId);
        materialChunkRepository.deleteByMaterialId(materialId);
        boolean deferVectorIndex = shouldDeferVectorIndexing(material.getSourceType(), chunks.size());
        saveChunks(material, chunks, true, !deferVectorIndex);
        savePageTextBlocks(material, parsed);

        updateMaterialParseProgress(material.getId(), material.getOwnerId(), 92, "生成预览", "正在生成阅读预览信息");
        if (!applyLargePdfFastPreviewMetadata(material, sourcePath, parsed)) {
            applyPreviewMetadata(material, sourcePath, material.getSourceType());
        }
        material.setParseStatus(MaterialParseStatus.SUCCESS);
        material.setParseProgressPercent(100);
        material.setParseStage("解析完成");
        material.setParseMessage("资料已经可以用于阅读和问答");
        material.setSummaryStatus(MaterialSummaryStatus.PENDING);
        material.setChunkCount(chunks.size());
        LearningMaterialEntity savedMaterial = learningMaterialRepository.save(material);
        if (deferVectorIndex) {
            scheduleVectorIndexRebuildAfterCommit(savedMaterial.getOwnerId(), savedMaterial.getId());
        }
        return toDetailResponse(savedMaterial);
    }

    /**
     * 重新排队抽取文本。接口立即返回，实际解析由数据库任务队列处理。
     */
    @Transactional
    public MaterialDetailResponse reparseText(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        materialProcessingJobService.cancelMaterialJobs(materialId, "资料重新解析，旧后台任务已取消");
        resetMaterialForTextPipeline(material);
        LearningMaterialEntity saved = learningMaterialRepository.save(material);
        materialProcessingJobService.enqueue(saved.getId(), MaterialProcessingJobType.EXTRACT_TEXT_FAST, 10, "重新解析文本", "等待后台重新抽取文本");
        return toDetailResponse(saved);
    }

    /**
     * 重新排队生成预览。预览失败不会阻断文本问答。
     */
    @Transactional
    public MaterialDetailResponse rebuildPreview(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        material.setPreviewStatus(MaterialPreviewStatus.NONE);
        material.setPreviewError(null);
        material.setProcessingStage("等待重建预览");
        material.setProcessingMessage("预览重建任务已排队");
        LearningMaterialEntity saved = learningMaterialRepository.save(material);
        materialProcessingJobService.enqueue(saved.getId(), MaterialProcessingJobType.BUILD_PREVIEW, 20, "重建预览", "等待后台重建阅读预览");
        return toDetailResponse(saved);
    }

    /**
     * 重新排队构建检索索引。BM25 已可用时仍允许问答，向量索引后台补齐。
     */
    @Transactional
    public MaterialDetailResponse rebuildIndex(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        material.setIndexStatus(MaterialIndexStatus.PARTIAL);
        material.setProcessingProgressPercent(Math.max(80, nullToZero(material.getProcessingProgressPercent())));
        material.setProcessingStage("等待重建索引");
        material.setProcessingMessage("向量索引重建任务已排队，BM25 可继续使用");
        LearningMaterialEntity saved = learningMaterialRepository.save(material);
        materialProcessingJobService.enqueue(saved.getId(), MaterialProcessingJobType.BUILD_EMBEDDING, 50, "构建向量", "等待重新生成 embedding");
        materialProcessingJobService.enqueue(saved.getId(), MaterialProcessingJobType.SYNC_VECTOR_STORE, 60, "同步向量库", "等待同步 Qdrant");
        return toDetailResponse(saved);
    }

    /**
     * 删除学习资料及其关联的所有数据。
     *
     * <p>删除范围包括：
     * <ul>
     *   <li>资料摘要记录</li>
     *   <li>RAG 问答来源关联</li>
     *   <li>向量数据库中的所有向量</li>
     *   <li>知识片段数据库记录</li>
     *   <li>资料数据库记录</li>
     *   <li>磁盘上的原始文件、预览 PDF 和图片资源目录</li>
     * </ul>
     * 操作不可撤销。
     *
     * @param ownerId    资料所有者用户 ID
     * @param materialId 资料 ID
     * @throws BusinessException 资料不存在时抛出 404
     */
    @Transactional
    public void delete(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        String storagePath = material.getStoragePath();
        materialProcessingJobService.cancelMaterialJobs(materialId, "资料已删除，后台任务已取消");
        materialSummaryRepository.deleteByMaterialIdAndUserId(materialId, ownerId);
        ragQuestionSourceRepository.deleteByMaterialId(materialId);
        materialPageRepository.deleteByMaterialId(materialId);
        materialPageTextBlockRepository.deleteByMaterialId(materialId);
        materialChunkRepository.deleteByMaterialId(materialId);
        materialUploadSessionRepository.deleteByMaterialId(materialId);
        learningMaterialRepository.delete(material);
        cleanupDeletedMaterialAfterCommit(ownerId, materialId, storagePath);
    }

    /**
     * 数据库删除提交后再清理外部向量和磁盘文件。
     * 外部向量库、原文、预览 PDF 和页面图片都不应该放在删除事务里执行，否则大文件清理会延长锁持有时间。
     */
    private void cleanupDeletedMaterialAfterCommit(long ownerId, long materialId, String storagePath) {
        runAfterCommit(() -> {
            try {
                vectorStoreClient.deleteMaterial(ownerId, materialId);
            } catch (Exception exception) {
                log.warn("Failed to delete material vectors after commit: materialId={}", materialId, exception);
            }
            cleanupStoredMaterialFiles(storagePath);
        });
    }

    /**
     * 只清理磁盘文件的事务后回调。
     * 分片会话尚未绑定资料记录时没有向量索引，只需要删除原文、预览和页面资源。
     */
    private void cleanupStoredMaterialFilesAfterCommit(String storagePath) {
        runAfterCommit(() -> cleanupStoredMaterialFiles(storagePath));
    }

    /** 清理资料在磁盘上的原文、预览文件和页面图片资源。 */
    private void cleanupStoredMaterialFiles(String storagePath) {
        deleteStoredAssets(storagePath);
        deleteStoredFile(storagePath);
        deletePreviewFile(storagePath);
    }

    /**
     * 当前有事务时延迟到提交后执行；没有事务时立即执行。
     * 这样删除接口和上传会话清理都能复用同一套提交后清理逻辑。
     */
    private void runAfterCommit(Runnable task) {
        if (task == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    /**
     * 调度后台处理任务。
     *
     * <p>如果当前在事务上下文中，会注册事务同步回调，在事务提交后再执行后台任务；
     * 否则直接提交到线程池执行。这样确保数据库事务先完成，后台任务读到的是已提交的数据。
     *
     * @param sessionId 上传会话 ID
     */
    private void scheduleProcessing(String sessionId) {
        if (sessionId == null || scheduledProcessingSessions.putIfAbsent(sessionId, Boolean.TRUE) != null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 后台线程依赖刚写入的 session/material 状态，必须等当前事务提交后再读取。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    if (inlineProcessingAfterUpload) {
                        processUploadSessionInExecutorForInlineTest(sessionId);
                        runProcessingJobsForInlineTest();
                    } else {
                        uploadExecutor.submit(() -> processUploadSession(sessionId));
                    }
                }
            });
            return;
        }
        if (inlineProcessingAfterUpload) {
            processUploadSessionInExecutorForInlineTest(sessionId);
            runProcessingJobsForInlineTest();
            return;
        }
        uploadExecutor.submit(() -> processUploadSession(sessionId));
    }

    /**
     * 测试环境同步等待上传会话处理完成。
     *
     * <p>Spring 的 afterCommit 回调执行时事务资源仍绑定在当前线程上；如果直接在该线程继续读写数据库，
     * 某些 Repository 调用会误复用已经提交的事务上下文，导致分片上传会话长期停在 PROCESSING。
     * 这里仍使用线上同一个上传线程池，只是在测试配置下等待任务结束，既保留真实线程模型，
     * 又让集成测试能稳定读取到合并、校验和排队后的状态。</p>
     */
    private void processUploadSessionInExecutorForInlineTest(String sessionId) {
        try {
            CompletableFuture
                .runAsync(() -> processUploadSession(sessionId), uploadExecutor)
                .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("inline upload processing was interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("inline upload processing did not finish", exception);
        }
    }

    /**
     * 恢复卡在 PROCESSING 的上传会话。
     *
     * <p>分片全部上传后，合并原文件运行在后台线程中；如果服务在这个窗口重启，
     * 旧实现不会再触发合并，导致用户看到“上传成功/处理中”，但资料实际不可用。
     * 定时恢复能把数据库中的 PROCESSING 会话重新提交到合并线程，保证大文件上传可恢复。</p>
     */
    @Scheduled(fixedDelayString = "${app.material-upload.recover-delay:30s}", initialDelayString = "${app.material-upload.recover-initial-delay:5s}")
    public void recoverProcessingUploadSessions() {
        List<MaterialUploadSessionEntity> sessions = materialUploadSessionRepository
            .findTop20ByStatusOrderByUpdatedAtAsc(MaterialUploadSessionStatus.PROCESSING);
        for (MaterialUploadSessionEntity session : sessions) {
            if (session.getSessionId() != null) {
                scheduleProcessing(session.getSessionId());
            }
        }
    }

    /**
     * 将资料的解析状态标记为"解析中"。
     *
     * <p>在上传会话进入处理阶段时调用，更新资料的解析状态和进度信息，
     * 让前端可以通过查询资料列表看到该资料正在解析。
     *
     * @param session 上传会话实体
     */
    private void markMaterialParsing(MaterialUploadSessionEntity session) {
        if (session.getMaterialId() == null) {
            return;
        }
        learningMaterialRepository.findByIdAndOwnerId(session.getMaterialId(), session.getOwnerId())
            .ifPresent(material -> {
                material.setParseStatus(MaterialParseStatus.PARSING);
                material.setParseProgressPercent(5);
                material.setParseStage("等待解析");
                material.setParseMessage("文件上传完成，后台解析即将开始");
                material.setPreviewStatus(MaterialPreviewStatus.NONE);
                material.setPreviewError(null);
                learningMaterialRepository.save(material);
            });
    }

    /**
     * 处理分片上传会话的后台任务（在独立线程中执行）。
     *
     * <p>完整处理流程：
     * <ol>
     *   <li>合并所有分片为一个完整文件</li>
     *   <li>校验合并后文件的 SHA-256 完整性（如果配置了校验值）</li>
     *   <li>解析文件提取文本（调用对应格式的解析器）</li>
     *   <li>将文本切分为知识片段（语义分块策略）</li>
     *   <li>生成预览元数据（PDF 页数等）</li>
     *   <li>删除旧的向量索引和知识片段</li>
     *   <li>为每个片段生成 Embedding 并写入向量数据库</li>
     *   <li>更新会话和资料状态为"成功"</li>
     *   <li>清理临时分片目录</li>
     * </ol>
     *
     * <p>异常处理：任何步骤失败时，将会话和资料状态标记为"失败"，
     * 清理已生成的文件和分片目录。
     *
     * @param sessionId 上传会话 ID
     */
    private void processUploadSession(String sessionId) {
        MaterialUploadSessionEntity session = materialUploadSessionRepository.findById(sessionId).orElse(null);
        try {
            if (session == null) {
                return;
            }
            if (session.getStatus() == MaterialUploadSessionStatus.SUCCESS) {
                return;
            }
            if (session.getStatus() != MaterialUploadSessionStatus.PROCESSING) {
                return;
            }
            Path finalPath = resolveStoredPath(session.getStoragePath());
            Files.createDirectories(finalPath.getParent());
            if (isCompleteStoredFile(session, finalPath)) {
                updateMaterialParseProgress(session, 12, "合并文件", "原文件已合并，继续完成入库");
            } else {
                updateMaterialParseProgress(session, 10, "合并文件", "正在合并上传分片");
                // 合并和整文件校验先完成，再进入解析，避免半文件被 PDFBox/ZIP 解析器消费。
                assembleUploadedFile(session, finalPath);
            }
            updateMaterialParseProgress(session, 18, "校验文件", "正在校验文件完整性");
            validateUploadedFileChecksum(session, finalPath);
            LearningMaterialEntity queuedMaterial = learningMaterialRepository.findByIdAndOwnerId(session.getMaterialId(), session.getOwnerId())
                .orElseThrow(() -> new BusinessException(404, "Material not found"));
            queuedMaterial.setTitle(session.getTitle());
            queuedMaterial.setSourceType(session.getSourceType());
            queuedMaterial.setOriginalName(session.getOriginalName());
            queuedMaterial.setStoragePath(storagePathValue(finalPath));
            queuedMaterial.setSourceUrl(session.getSourceUrl());
            queuedMaterial.setFileSize(session.getFileSize());
            queuedMaterial.setUploadStatus(MaterialUploadStatus.UPLOADED);
            queuedMaterial.setTextStatus(MaterialTextStatus.PENDING);
            queuedMaterial.setIndexStatus(MaterialIndexStatus.PENDING);
            queuedMaterial.setOcrStatus(ocrEnabled ? MaterialOcrStatus.PENDING : MaterialOcrStatus.DISABLED);
            queuedMaterial.setProcessingProgressPercent(5);
            queuedMaterial.setProcessingStage("等待后台解析");
            queuedMaterial.setProcessingMessage("文件已上传完成，后台任务即将抽取文本和构建索引");
            queuedMaterial.setParseStatus(MaterialParseStatus.PENDING);
            queuedMaterial.setParseProgressPercent(5);
            queuedMaterial.setParseStage("等待后台解析");
            queuedMaterial.setParseMessage("文件已上传完成，后台任务即将开始");
            learningMaterialRepository.save(queuedMaterial);
            materialProcessingJobService.enqueue(queuedMaterial.getId(), MaterialProcessingJobType.EXTRACT_TEXT_FAST, 10, "提取文本", "等待抽取资料文本");
            session.setUploadedChunks(session.getTotalChunks());
            session.setStatus(MaterialUploadSessionStatus.SUCCESS);
            session.setMaterialId(queuedMaterial.getId());
            session.setErrorMessage(null);
            materialUploadSessionRepository.save(session);
            recordMaterialLog(queuedMaterial.getOwnerId(), "UPLOAD_MATERIAL", queuedMaterial.getId(), queuedMaterial.getTitle(), queuedMaterial.getOriginalName(), queuedMaterial.getFileSize());
            cleanupPartDir(session);
        } catch (Exception exception) {
            if (exception instanceof BusinessException) {
                log.warn("Upload session rejected by business validation: sessionId={}, materialId={}, message={}",
                    sessionId,
                    session == null ? null : session.getMaterialId(),
                    exception.getMessage());
            } else {
                log.error("Upload session processing failed: sessionId={}, materialId={}",
                    sessionId,
                    session == null ? null : session.getMaterialId(),
                    exception);
            }
            MaterialUploadSessionEntity failedSession = materialUploadSessionRepository.findById(sessionId).orElse(null);
            if (failedSession != null) {
                failedSession.setStatus(MaterialUploadSessionStatus.FAILED);
                failedSession.setErrorMessage(exception.getMessage() == null ? "upload processing failed" : exception.getMessage());
                materialUploadSessionRepository.save(failedSession);
                if (failedSession.getMaterialId() != null) {
                    learningMaterialRepository.findByIdAndOwnerId(failedSession.getMaterialId(), failedSession.getOwnerId())
                        .ifPresent(material -> {
                            material.setUploadStatus(MaterialUploadStatus.FAILED);
                            material.setTextStatus(MaterialTextStatus.FAILED);
                            material.setIndexStatus(MaterialIndexStatus.FAILED);
                            material.setParseStatus(MaterialParseStatus.FAILED);
                            material.setParseStage("解析失败");
                            material.setParseMessage(failedSession.getErrorMessage());
                            material.setProcessingProgressPercent(100);
                            material.setProcessingStage("上传处理失败");
                            material.setProcessingMessage(failedSession.getErrorMessage());
                            learningMaterialRepository.save(material);
                        });
                }
                deleteStoredAssets(failedSession.getStoragePath());
                deletePreviewFile(failedSession.getStoragePath());
                deleteStoredFile(failedSession.getStoragePath());
                cleanupPartDir(failedSession);
            }
        } finally {
            scheduledProcessingSessions.remove(sessionId);
        }
    }

    /**
     * 为大 PDF 生成轻量页列表。
     *
     * <p>阅读器已经统一按 A4 纸张比例展示 PDF，前端 PDF.js 会自行读取真实页面并渲染；
     * 后端这里只需要给出页码和 chunk 映射即可。这样避免用户打开 300MB PDF 阅读器时，
     * 服务端为了读取 MediaBox 再次把整份 PDF 交给 PDFBox，导致 SSH/HTTP 被内存或 I/O 拖住。</p>
     */
    private List<MaterialPageResponse> lightweightPdfPages(LearningMaterialEntity material) {
        int pageCount = material.getPageCount() == null ? 0 : material.getPageCount();
        if (pageCount <= 0 || material.getId() == null) {
            return List.of();
        }
        Map<Integer, List<Long>> pageChunks = chunksByPage(material.getId(), pageCount);
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        List<MaterialPageResponse> pages = new ArrayList<>();
        for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
            String imageName = pageImageName(pageNo);
            pages.add(new MaterialPageResponse(
                pageNo,
                DEFAULT_PDF_PAGE_WIDTH,
                DEFAULT_PDF_PAGE_HEIGHT,
                imageName,
                pageChunks.getOrDefault(pageNo, List.of()),
                isPageRendered(sourcePath, imageName) ? "READY" : "PENDING"
            ));
        }
        return pages;
    }

    /**
     * 合并所有分片为一个完整文件。
     *
     * <p>按分片索引顺序（0, 1, 2, ...）依次将每个分片文件写入最终文件，
     * 每写入一个分片后立即删除该分片文件（节省磁盘空间）。
     * 如果某个分片缺失，抛出 400 异常。
     *
     * @param session   上传会话实体（包含总分片数和存储路径信息）
     * @param finalPath 最终合并文件的输出路径
     * @throws IOException 文件读写失败时抛出
     */
    private void assembleUploadedFile(MaterialUploadSessionEntity session, Path finalPath) throws IOException {
        Path partDir = partDir(session);
        validateAllUploadedParts(session);
        try (var output = Files.newOutputStream(finalPath)) {
            for (int index = 0; index < session.getTotalChunks(); index++) {
                Path partPath = partDir.resolve(partFileName(index));
                // 按固定序号顺序拼接，任何缺片都立即中止，避免生成可解析但内容错位的文件。
                if (!Files.exists(partPath)) {
                    throw new BusinessException(400, "Missing upload chunk");
                }
                Files.copy(partPath, output);
                // 已写入最终文件的分片立刻删除，降低大文件上传的临时磁盘占用。
                Files.deleteIfExists(partPath);
            }
        }
    }

    /**
     * 刷新上传会话的进度信息。
     *
     * <p>扫描分片目录计算实际已上传的分片数，更新到数据库。
     *
     * @param session 上传会话实体
     */
    private void refreshSessionProgress(MaterialUploadSessionEntity session) {
        session.setUploadedChunks(uploadedPartIndexes(session).size());
        materialUploadSessionRepository.save(session);
    }

    /**
     * 判断所有分片是否都已按序存在。
     *
     * <p>不能只统计 .part 文件数量，因为目录里如果缺少中间片但多出异常文件，数量判断会误报完成。
     */
    private boolean hasAllUploadedParts(MaterialUploadSessionEntity session) {
        try {
            validateAllUploadedParts(session);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    /**
     * 判断最终原文件是否已经完整存在。
     *
     * <p>恢复 PROCESSING 会话时可能遇到“文件已合并、会话还没写 SUCCESS、分片已清理”的中间态。
     * 此时不能再强制要求分片存在，应直接复用完整原文件继续入库和创建后台任务。</p>
     */
    private boolean isCompleteStoredFile(MaterialUploadSessionEntity session, Path finalPath) {
        if (session == null || finalPath == null || session.getFileSize() == null) {
            return false;
        }
        try {
            return Files.exists(finalPath)
                && Files.isRegularFile(finalPath)
                && Files.size(finalPath) == session.getFileSize();
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * 合并前逐片校验文件存在性和大小。
     *
     * <p>除最后一片外，每片必须等于会话 chunkSize；最后一片必须大于 0 且不超过 chunkSize。
     * 这样可以尽早发现断点续传、网关截断或并发覆盖造成的不完整上传。
     */
    private void validateAllUploadedParts(MaterialUploadSessionEntity session) {
        if (session == null || session.getTotalChunks() == null || session.getChunkSize() == null || session.getFileSize() == null) {
            throw new BusinessException(400, "Invalid upload session");
        }
        Path partDir = partDir(session);
        for (int index = 0; index < session.getTotalChunks(); index++) {
            Path partPath = partDir.resolve(partFileName(index));
            if (!Files.exists(partPath) || !Files.isRegularFile(partPath)) {
                throw new BusinessException(400, "Missing upload chunk: " + index);
            }
            try {
                long actualSize = Files.size(partPath);
                long expectedSize = expectedPartSize(session, index);
                if (actualSize != expectedSize) {
                    throw new BusinessException(400, "Invalid upload chunk size: " + index);
                }
            } catch (IOException exception) {
                throw new BusinessException(500, "failed to read upload chunk");
            }
        }
    }

    private long expectedPartSize(MaterialUploadSessionEntity session, int index) {
        long chunkSize = session.getChunkSize();
        long fileSize = session.getFileSize();
        long start = (long) index * chunkSize;
        return Math.min(chunkSize, fileSize - start);
    }

    /**
     * 统计分片目录中已存在的 .part 文件数量。
     *
     * @param session 上传会话实体
     * @return 已上传的分片数量
     */
    private int countUploadedParts(MaterialUploadSessionEntity session) {
        return uploadedPartIndexes(session).size();
    }

    /**
     * 读取已经落盘的分片索引。
     *
     * <p>前端并发上传时可能只完成了中间某些分片，如果只返回数量，断点续传会误以为
     * 0..N 的分片都已经上传。这里返回真实索引，前端即可只补传缺失分片。</p>
     *
     * @param session 上传会话实体
     * @return 已上传且文件名合法的分片索引，按升序排列
     */
    private List<Integer> uploadedPartIndexes(MaterialUploadSessionEntity session) {
        Path partDir = partDir(session);
        if (!Files.exists(partDir)) {
            return List.of();
        }
        try (var stream = Files.list(partDir)) {
            return stream
                .map(path -> uploadedPartIndex(path.getFileName().toString()))
                .flatMap(Optional::stream)
                .filter(index -> index >= 0 && (session.getTotalChunks() == null || index < session.getTotalChunks()))
                .distinct()
                .sorted()
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private Optional<Integer> uploadedPartIndex(String fileName) {
        Matcher matcher = Pattern.compile("^(\\d{6})\\.part$").matcher(fileName == null ? "" : fileName);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Integer.parseInt(matcher.group(1)));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    /**
     * 要求上传会话存在且属于指定用户。
     *
     * @param ownerId   用户 ID
     * @param sessionId 会话 ID
     * @return 上传会话实体
     * @throws BusinessException 会话不存在或不属于该用户时抛出 404
     */
    private MaterialUploadSessionEntity requireUploadSession(long ownerId, String sessionId) {
        return materialUploadSessionRepository.findById(sessionId)
            .filter(session -> session.getOwnerId() != null && session.getOwnerId() == ownerId)
            .orElseThrow(() -> new BusinessException(404, "Upload session not found"));
    }

    /**
     * 丢弃可重建的上传会话及其关联的资料数据。
     *
     * <p>清理范围：分片目录、原始文件、预览文件、资源目录、
     * 资料摘要、问答来源、向量索引、知识片段和资料记录本身。
     * 用于幂等创建时遇到失败状态，或旧前端分片策略留下的未完成会话需要重新创建的场景。
     *
     * @param session 可丢弃的上传会话实体
     */
    private void discardUploadSession(MaterialUploadSessionEntity session) {
        String storagePath = session.getStoragePath();
        cleanupPartDir(session);
        materialUploadSessionRepository.delete(session);
        materialUploadSessionRepository.flush();
        if (session.getMaterialId() == null) {
            cleanupStoredMaterialFilesAfterCommit(storagePath);
            return;
        }
        learningMaterialRepository.findByIdAndOwnerId(session.getMaterialId(), session.getOwnerId())
            .ifPresentOrElse(material -> {
                materialProcessingJobService.cancelMaterialJobs(material.getId(), "上传会话已丢弃，后台任务已取消");
                String materialStoragePath = material.getStoragePath() == null ? storagePath : material.getStoragePath();
                materialSummaryRepository.deleteByMaterialIdAndUserId(material.getId(), material.getOwnerId());
                ragQuestionSourceRepository.deleteByMaterialId(material.getId());
                materialPageRepository.deleteByMaterialId(material.getId());
                materialPageTextBlockRepository.deleteByMaterialId(material.getId());
                materialChunkRepository.deleteByMaterialId(material.getId());
                learningMaterialRepository.delete(material);
                cleanupDeletedMaterialAfterCommit(material.getOwnerId(), material.getId(), materialStoragePath);
            }, () -> {
                cleanupStoredMaterialFilesAfterCommit(storagePath);
            });
    }

    /**
     * 判断已有上传会话的元数据与新请求是否一致（用于幂等性检查）。
     *
     * <p>检查标题、文件名、来源类型、来源 URL、文件大小、分片大小和总分片数是否完全一致。
     * 分片大小变更后，旧的 UPLOADING 残留会话会和新请求不一致，调用方会按状态决定是否清理重建。
     */
    private boolean sameUploadSessionMetadata(
        MaterialUploadSessionEntity session,
        String title,
        String originalName,
        MaterialSourceType sourceType,
        String sourceUrl,
        long fileSize,
        int chunkSize,
        int totalChunks
    ) {
        return Objects.equals(normalizeText(session.getTitle(), ""), title)
            && Objects.equals(normalizeText(session.getOriginalName(), ""), originalName)
            && session.getSourceType() == sourceType
            && Objects.equals(normalizeOptionalText(session.getSourceUrl()), sourceUrl)
            && Objects.equals(session.getFileSize(), fileSize)
            && Objects.equals(session.getChunkSize(), chunkSize)
            && Objects.equals(session.getTotalChunks(), totalChunks);
    }

    /**
     * 判断旧上传会话是否可以自动丢弃。
     *
     * <p>只有尚未完成的 UPLOADING 和明确失败的 FAILED 可以重建；
     * PROCESSING/SUCCESS 代表资料可能正在解析或已经可用，继续保留 409 保护，避免误删有效资料。
     */
    private boolean isDiscardableUploadSession(MaterialUploadSessionEntity session) {
        return session.getStatus() == MaterialUploadSessionStatus.UPLOADING
            || session.getStatus() == MaterialUploadSessionStatus.FAILED;
    }

    /**
     * 创建已落盘、待后台处理的资料记录。
     *
     * <p>上传接口只负责确认文件已经保存，随后创建数据库任务让 worker 解析、切片和索引。
     * 这样大 PDF 不会阻塞上传请求，也不会因为单个文件解析耗时影响后续小文件上传。</p>
     */
    private MaterialResponse createQueuedMaterial(
        long ownerId,
        String title,
        MaterialSourceType sourceType,
        String originalName,
        Path storagePath,
        String sourceUrl,
        long fileSize
    ) {
        LearningMaterialEntity material = new LearningMaterialEntity();
        material.setOwnerId(ownerId);
        material.setTitle(normalizeTitle(title, originalName));
        material.setSourceType(sourceType);
        material.setOriginalName(originalName);
        material.setStoragePath(storagePathValue(storagePath));
        material.setSourceUrl(normalizeOptionalText(sourceUrl));
        material.setFileSize(fileSize);
        material.setParseStatus(MaterialParseStatus.PENDING);
        material.setParseProgressPercent(0);
        material.setParseStage("等待后台解析");
        material.setParseMessage("文件已上传完成，后台任务即将开始");
        material.setUploadStatus(MaterialUploadStatus.UPLOADED);
        material.setTextStatus(MaterialTextStatus.PENDING);
        material.setIndexStatus(MaterialIndexStatus.PENDING);
        material.setOcrStatus(ocrEnabled ? MaterialOcrStatus.PENDING : MaterialOcrStatus.DISABLED);
        material.setProcessingProgressPercent(0);
        material.setProcessingStage("等待后台解析");
        material.setProcessingMessage("文件已上传完成，后台任务即将开始");
        material.setIndexedChunkCount(0);
        material.setTextPageCount(0);
        material.setSummaryStatus(MaterialSummaryStatus.PENDING);
        material.setPreviewStatus(MaterialPreviewStatus.NONE);
        material.setChunkCount(0);
        LearningMaterialEntity saved = learningMaterialRepository.save(material);
        materialProcessingJobService.enqueue(saved.getId(), MaterialProcessingJobType.EXTRACT_TEXT_FAST, 10, "提取文本", "等待抽取资料文本");
        runQueuedMaterialAfterCommitForInlineTest();
        recordMaterialLog(ownerId, "UPLOAD_MATERIAL", saved.getId(), saved.getTitle(), saved.getOriginalName(), saved.getFileSize());
        return toResponse(saved);
    }

    /**
     * 测试环境的同步化辅助。
     *
     * <p>线上上传仍然只入库排队；集成测试需要紧接着对资料问答、摘要、后台队列做断言，
     * 因此通过配置在事务提交后立即消费一批刚创建的任务，保持测试语义稳定。</p>
     */
    private void runQueuedMaterialAfterCommitForInlineTest() {
        if (!inlineProcessingAfterUpload) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runProcessingJobsForInlineTest();
                }
            });
            return;
        }
        runProcessingJobsForInlineTest();
    }

    /** 测试环境一次性多跑几轮队列，覆盖文本抽取后继续排出的预览、切片和索引状态任务。 */
    private void runProcessingJobsForInlineTest() {
        for (int i = 0; i < 8; i++) {
            int executed = materialProcessingJobService.runReadyJobs(16);
            if (executed == 0) {
                return;
            }
        }
    }

    /**
     * 保存学习资料到数据库（同步导入兼容路径）。
     */
    private MaterialResponse saveMaterial(
        long ownerId,
        String title,
        MaterialSourceType sourceType,
        String originalName,
        Path storagePath,
        String sourceUrl,
        long fileSize,
        ParsedMaterial parsed
    ) {
        if (parsed == null || parsed.isBlank()) {
            throw new BusinessException(400, "material parsing failed");
        }
        List<ChunkDraft> chunks = chunkMaterial(parsed);
        LearningMaterialEntity material = new LearningMaterialEntity();
        material.setOwnerId(ownerId);
        material.setTitle(normalizeTitle(title, originalName));
        material.setSourceType(sourceType);
        material.setOriginalName(originalName);
        material.setStoragePath(storagePathValue(storagePath));
        material.setSourceUrl(normalizeOptionalText(sourceUrl));
        material.setFileSize(fileSize);
        material.setParseStatus(MaterialParseStatus.SUCCESS);
        material.setParseProgressPercent(100);
        material.setParseStage("解析完成");
        material.setParseMessage("资料已经可以用于阅读和问答");
        material.setUploadStatus(MaterialUploadStatus.UPLOADED);
        material.setTextStatus(MaterialTextStatus.READY);
        material.setIndexStatus(MaterialIndexStatus.READY);
        material.setOcrStatus(ocrEnabled ? MaterialOcrStatus.READY : MaterialOcrStatus.DISABLED);
        material.setProcessingProgressPercent(100);
        material.setProcessingStage("解析完成");
        material.setProcessingMessage("资料已经可以用于阅读和问答");
        material.setIndexedChunkCount(chunks.size());
        material.setTextPageCount(resolveTextPageCount(parsed));
        material.setSummaryStatus(MaterialSummaryStatus.PENDING);
        if (!applyLargePdfFastPreviewMetadata(material, storagePath, parsed)) {
            applyPreviewMetadata(material, storagePath, sourceType);
        }
        material.setChunkCount(chunks.size());
        LearningMaterialEntity saved = learningMaterialRepository.save(material);
        saveChunks(saved, chunks, false, true);
        savePageTextBlocks(saved, parsed);
        saveMaterialPages(saved, parsed);
        recordMaterialLog(ownerId, "UPLOAD_MATERIAL", saved.getId(), saved.getTitle(), saved.getOriginalName(), saved.getFileSize());
        return toResponse(saved);
    }

    /**
     * 执行文本抽取、预览元数据、页级文本、切片和 BM25 数据库索引。
     */
    private void runTextExtractionPipeline(Long materialId, Long jobId) {
        LearningMaterialEntity material = learningMaterialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
            throw new BusinessException(404, "Original material file is missing. Please upload this material again.");
        }

        material.setUploadStatus(MaterialUploadStatus.UPLOADED);
        material.setTextStatus(MaterialTextStatus.RUNNING);
        material.setIndexStatus(MaterialIndexStatus.PENDING);
        material.setProcessingProgressPercent(15);
        material.setProcessingStage("提取文本");
        material.setProcessingMessage("正在从资料中抽取文本层");
        learningMaterialRepository.saveAndFlush(material);

        ParsedMaterial parsed = parseMaterial(material.getSourceType(), sourcePath, (percent, stage, message) ->
            updateMaterialParseProgress(material.getId(), material.getOwnerId(), Math.min(70, Math.max(15, percent)), stage, message));
        if (shouldAbortProcessingJob(jobId, materialId)) {
            return;
        }
        if (parsed.isBlank()) {
            material.setTextStatus(MaterialTextStatus.FAILED);
            material.setIndexStatus(MaterialIndexStatus.FAILED);
            material.setProcessingProgressPercent(100);
            material.setProcessingStage("文本抽取失败");
            material.setProcessingMessage("未提取到可用于问答的文本");
            learningMaterialRepository.save(material);
            throw new BusinessException(400, "material parsing failed");
        }

        updateMaterialParseProgress(material.getId(), material.getOwnerId(), 72, "切分文本", "正在生成知识片段和页级文本");
        List<ChunkDraft> chunks = chunkMaterial(parsed);
        if (shouldAbortProcessingJob(jobId, materialId)) {
            return;
        }
        materialSummaryRepository.deleteByMaterialIdAndUserId(material.getId(), material.getOwnerId());
        ragQuestionSourceRepository.deleteByMaterialId(material.getId());
        vectorStoreClient.deleteMaterial(material.getOwnerId(), material.getId());
        materialPageRepository.deleteByMaterialId(material.getId());
        materialPageTextBlockRepository.deleteByMaterialId(material.getId());
        materialChunkRepository.deleteByMaterialId(material.getId());

        if (!applyLargePdfFastPreviewMetadata(material, sourcePath, parsed)) {
            applyPreviewMetadata(material, sourcePath, material.getSourceType());
        }
        boolean partialLargePdfImport = isPartialLargePdfImport(material, sourcePath, parsed);
        boolean imagePlaceholderOnly = isImagePlaceholderOnly(parsed);
        material.setChunkCount(chunks.size());
        material.setTextPageCount(partialLargePdfImport
            ? Math.min(largePdfFastImportMaxTextPages, parsed.pageCount() == null ? largePdfFastImportMaxTextPages : parsed.pageCount())
            : resolveTextPageCount(parsed));
        material.setIndexedChunkCount(chunks.size());
        material.setTextStatus(partialLargePdfImport || imagePlaceholderOnly ? MaterialTextStatus.PARTIAL : MaterialTextStatus.READY);
        material.setIndexStatus(partialLargePdfImport || imagePlaceholderOnly ? MaterialIndexStatus.PARTIAL : MaterialIndexStatus.READY);
        material.setOcrStatus(resolveOcrStatus(parsed, imagePlaceholderOnly));
        material.setProcessingProgressPercent(partialLargePdfImport ? 85 : 100);
        material.setProcessingStage(resolveTextExtractionStage(partialLargePdfImport, imagePlaceholderOnly));
        material.setProcessingMessage(resolveTextExtractionMessage(material, parsed, partialLargePdfImport, imagePlaceholderOnly));
        learningMaterialRepository.save(material);

        saveChunks(material, chunks, false, false);
        savePageTextBlocks(material, parsed);
        saveMaterialPages(material, parsed);
        if (partialLargePdfImport) {
            materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.EXTRACT_TEXT_REMAINING, 15, "补齐剩余页面", "继续抽取大 PDF 后续页面文本");
        }
        if (imagePlaceholderOnly && ocrEnabled) {
            materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.OCR_PAGE_BATCH, 18, "OCR 识别", "图片型 PDF 页面已入库，等待后台 OCR 分批识别");
        }
        materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.BUILD_PREVIEW, 20, "生成预览", "预览元数据已生成");
        materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.CHUNK_TEXT, 30, "切分文本", "文本切片已生成");
        materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.BUILD_BM25, 40, "构建 BM25", "数据库关键词索引已可用");
        boolean deferVectorIndexUntilOcrReady = imagePlaceholderOnly && ocrEnabled;
        if (!deferVectorIndexUntilOcrReady) {
            materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.BUILD_EMBEDDING, 50, "构建向量", "等待生成 chunk embedding");
            materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.SYNC_VECTOR_STORE, 60, "同步向量库", "等待同步 Qdrant");
        }
    }

    /**
     * 按页批量补齐图片型 PDF 的 OCR 文本。
     *
     * <p>大 PDF 初次导入只负责快速建立页级占位和预览，不能在上传链路内同步 OCR 数百页。
     * 这里每次只处理少量仍处于 PENDING/PARTIAL/RUNNING 的占位页，成功页会替换原 chunk、page
     * 和文本层；剩余页继续排队，直到全部完成或确认无法识别。</p>
     */
    private void runOcrPageBatchPipeline(Long materialId, Long jobId) {
        LearningMaterialEntity material = learningMaterialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        if (shouldAbortProcessingJob(jobId, materialId)) {
            return;
        }
        if (!ocrEnabled) {
            material.setOcrStatus(MaterialOcrStatus.DISABLED);
            learningMaterialRepository.save(material);
            return;
        }
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        if (material.getSourceType() != MaterialSourceType.PDF || !Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
            throw new BusinessException(404, "Original material file is missing. Please upload this material again.");
        }
        List<MaterialPageEntity> pages = materialPageRepository.findByMaterialIdOrderByPageNoAsc(materialId);
        List<MaterialPageEntity> pendingPages = pendingOcrPlaceholderPages(pages);
        if (pendingPages.isEmpty() && material.getOcrStatus() == MaterialOcrStatus.FAILED) {
            // 旧版本会把图片型 PDF 的占位页全部标记为 FAILED，却没有留下 OCR_PAGE_BATCH 任务。
            // 当用户点击重新解析或运维补排 OCR 任务时，先把这些旧失败页恢复为 PENDING，随后仍按小批量继续处理。
            resetFailedOcrPlaceholderPages(pages);
            pages = materialPageRepository.findByMaterialIdOrderByPageNoAsc(materialId);
            pendingPages = pendingOcrPlaceholderPages(pages);
        }
        if (pendingPages.isEmpty()) {
            summarizeOcrBatchState(material);
            return;
        }

        material.setOcrStatus(MaterialOcrStatus.RUNNING);
        material.setTextStatus(MaterialTextStatus.PARTIAL);
        material.setIndexStatus(MaterialIndexStatus.PARTIAL);
        material.setProcessingProgressPercent(ocrProgressPercent(pages));
        material.setProcessingStage("OCR 后台识别");
        material.setProcessingMessage("正在 OCR 识别图片型 PDF 第 " + pendingPages.get(0).getPageNo()
            + "-" + pendingPages.get(pendingPages.size() - 1).getPageNo() + " 页");
        learningMaterialRepository.saveAndFlush(material);

        int recognizedPages = 0;
        try (var document = Loader.loadPDF(sourcePath.toFile())) {
            for (MaterialPageEntity page : pendingPages) {
                if (shouldAbortProcessingJob(jobId, materialId)) {
                    return;
                }
                page.setOcrStatus(MaterialOcrStatus.RUNNING);
                materialPageRepository.save(page);
                int pageNo = page.getPageNo() == null || page.getPageNo() <= 0 ? 1 : page.getPageNo();
                ScannedPdfPageResult result = scannedPdfPageTextLayer(sourcePath, document, pageNo - 1, pageNo, true);
                if (shouldAbortProcessingJob(jobId, materialId)) {
                    return;
                }
                if (result.text() != null && !isImagePlaceholderText(result.text())) {
                    replacePlaceholderPageWithOcrText(material, page, result);
                    recognizedPages += 1;
                } else {
                    page.setOcrStatus(MaterialOcrStatus.FAILED);
                    page.setTextStatus(MaterialTextStatus.PARTIAL);
                    materialPageRepository.save(page);
                }
            }
        } catch (IOException exception) {
            throw new BusinessException(400, "OCR PDF load failed");
        }

        if (shouldAbortProcessingJob(jobId, materialId)) {
            return;
        }
        summarizeOcrBatchState(material);
    }

    /** 判断某页是否仍是等待 OCR 的图片占位页。 */
    private boolean isPendingOcrPlaceholderPage(MaterialPageEntity page) {
        if (page == null || !isImagePlaceholderText(page.getText())) {
            return false;
        }
        return page.getOcrStatus() == MaterialOcrStatus.PENDING
            || page.getOcrStatus() == MaterialOcrStatus.PARTIAL
            || page.getOcrStatus() == MaterialOcrStatus.RUNNING;
    }

    /** 查询本轮要处理的图片占位页，统一限制每批页数，避免大扫描件一次性占满 OCR 进程。 */
    private List<MaterialPageEntity> pendingOcrPlaceholderPages(List<MaterialPageEntity> pages) {
        if (pages == null || pages.isEmpty()) {
            return List.of();
        }
        return pages.stream()
            .filter(this::isPendingOcrPlaceholderPage)
            .limit(OCR_PAGE_BATCH_SIZE)
            .toList();
    }

    /** 将旧版本遗留的失败占位页恢复为可重试状态；只有显式触发 OCR_PAGE_BATCH 时才会执行。 */
    private void resetFailedOcrPlaceholderPages(List<MaterialPageEntity> pages) {
        if (pages == null || pages.isEmpty()) {
            return;
        }
        List<MaterialPageEntity> failedPlaceholderPages = pages.stream()
            .filter(page -> page != null
                && page.getOcrStatus() == MaterialOcrStatus.FAILED
                && isImagePlaceholderText(page.getText()))
            .toList();
        if (failedPlaceholderPages.isEmpty()) {
            return;
        }
        failedPlaceholderPages.forEach(page -> page.setOcrStatus(MaterialOcrStatus.PENDING));
        materialPageRepository.saveAll(failedPlaceholderPages);
    }

    /** 用 OCR 结果替换单页占位 page、chunk 和文本层。 */
    private void replacePlaceholderPageWithOcrText(
        LearningMaterialEntity material,
        MaterialPageEntity page,
        ScannedPdfPageResult result
    ) {
        String text = result.text().trim();
        page.setText(text);
        page.setTextStatus(MaterialTextStatus.READY);
        page.setOcrStatus(MaterialOcrStatus.READY);
        page.setCharCount(text.length());
        page.setTokenCount(estimateTokenCount(text));
        materialPageRepository.save(page);

        MaterialChunkEntity chunk = upsertOcrPageChunk(material, page.getPageNo(), text);
        replacePageTextLayerWithOcr(material.getId(), page.getPageNo(), chunk.getId(), result);
    }

    /** 找到单页占位 chunk 并替换为 OCR 文本；缺失时兜底新建一个页级 chunk。 */
    private MaterialChunkEntity upsertOcrPageChunk(LearningMaterialEntity material, int pageNo, String text) {
        List<MaterialChunkEntity> chunks = materialChunkRepository.findByMaterialIdAndPageNoOrderByChunkIndexAsc(material.getId(), pageNo);
        MaterialChunkEntity chunk = chunks.isEmpty() ? new MaterialChunkEntity() : chunks.get(0);
        if (chunk.getId() == null) {
            int nextIndex = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(material.getId()).size();
            chunk.setMaterialId(material.getId());
            chunk.setChunkIndex(nextIndex);
        }
        chunk.setChunkText(text);
        chunk.setPageNo(pageNo);
        chunk.setSourcePageStart(pageNo);
        chunk.setSourcePageEnd(pageNo);
        chunk.setSectionTitle("Page " + pageNo);
        chunk.setHierarchyPath(buildHierarchyPath(material, chunk, chunk.getChunkIndex() == null ? 0 : chunk.getChunkIndex()));
        chunk.setSummary(buildChunkSummary(text));
        chunk.setKeywords(buildChunkKeywords(text));
        chunk.setEmbeddingJson(null);
        chunk.setCharCount(text.length());
        chunk.setTokenCount(estimateTokenCount(text));
        chunk.setEmbeddingStatus(MaterialIndexStatus.PENDING);
        chunk.setIndexStatus(MaterialIndexStatus.READY);
        return materialChunkRepository.save(chunk);
    }

    /** 删除旧占位文本层并写入 OCR 文本层；没有坐标时使用整页兜底文本层。 */
    private void replacePageTextLayerWithOcr(
        Long materialId,
        Integer pageNo,
        Long chunkId,
        ScannedPdfPageResult result
    ) {
        materialPageTextBlockRepository.deleteByMaterialIdAndPageNo(materialId, pageNo);
        List<MaterialPageTextBlockEntity> blocks = new ArrayList<>();
        Map<Integer, Integer> blockIndexByPage = new LinkedHashMap<>();
        List<PageTextLayerDraft> layers = result.textLayers() == null ? List.of() : result.textLayers();
        if (!layers.isEmpty()) {
            for (PageTextLayerDraft layer : layers) {
                appendPageTextBlocks(
                    blocks,
                    blockIndexByPage,
                    materialId,
                    pageNo,
                    layer.text(),
                    normalizeText(layer.blockType(), "ocr-line"),
                    normalizeText(layer.source(), "OCR"),
                    chunkId,
                    layer.pageWidth(),
                    layer.pageHeight(),
                    layer.bboxX(),
                    layer.bboxY(),
                    layer.bboxWidth(),
                    layer.bboxHeight(),
                    layer.confidence()
                );
            }
        }
        if (blocks.isEmpty()) {
            appendPageTextBlocks(
                blocks,
                blockIndexByPage,
                materialId,
                pageNo,
                cleanEmbeddingText(result.text()),
                "ocr",
                "OCR",
                chunkId,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );
        }
        if (!blocks.isEmpty()) {
            materialPageTextBlockRepository.saveAll(blocks);
        }
    }

    /** 汇总整份资料的 OCR 状态，并在仍有待处理页时继续排队。 */
    private void summarizeOcrBatchState(LearningMaterialEntity material) {
        List<MaterialPageEntity> pages = materialPageRepository.findByMaterialIdOrderByPageNoAsc(material.getId());
        long ready = pages.stream().filter(page -> page.getOcrStatus() == MaterialOcrStatus.READY).count();
        long pending = pages.stream().filter(this::isPendingOcrPlaceholderPage).count();
        long total = pages.size();
        if (pending > 0) {
            material.setOcrStatus(ready > 0 ? MaterialOcrStatus.PARTIAL : MaterialOcrStatus.RUNNING);
            material.setTextStatus(MaterialTextStatus.PARTIAL);
            material.setIndexStatus(MaterialIndexStatus.PARTIAL);
            material.setProcessingProgressPercent(ocrProgressPercent(pages));
            material.setProcessingStage("OCR 后台识别");
            material.setProcessingMessage("已 OCR " + ready + "/" + total + " 页，剩余页面继续后台识别");
            learningMaterialRepository.save(material);
            materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.OCR_PAGE_BATCH, 18, "OCR 识别", "继续分批 OCR 图片型 PDF 页面");
            return;
        }
        material.setOcrStatus(ready > 0 && ready == total ? MaterialOcrStatus.READY : (ready > 0 ? MaterialOcrStatus.PARTIAL : MaterialOcrStatus.FAILED));
        material.setTextStatus(ready == total && total > 0 ? MaterialTextStatus.READY : MaterialTextStatus.PARTIAL);
        material.setIndexStatus(ready > 0 ? MaterialIndexStatus.PENDING : MaterialIndexStatus.PARTIAL);
        material.setProcessingProgressPercent(100);
        material.setProcessingStage(ready > 0 ? "OCR 已完成" : "图片页已入库");
        material.setProcessingMessage(ready > 0
            ? "OCR 已识别 " + ready + "/" + total + " 页，资料可用于阅读和问答，向量增强继续后台补齐"
            : "PDF 页面已按页入库，但 OCR 未识别到可检索正文；请检查 OCR 依赖或改用多模态问答");
        learningMaterialRepository.save(material);
        if (ready > 0) {
            materialProcessingJobService.enqueueIfNoActiveJob(
                material.getId(),
                MaterialProcessingJobType.BUILD_EMBEDDING,
                50,
                "构建向量",
                "OCR 文本已完成，等待生成 embedding"
            );
            materialProcessingJobService.enqueueIfNoActiveJob(
                material.getId(),
                MaterialProcessingJobType.SYNC_VECTOR_STORE,
                60,
                "同步向量库",
                "等待同步 Qdrant"
            );
        }
    }

    /** 按真实 OCR 页数计算进度，避免 200MB 扫描 PDF 明明在跑却长期显示 0% 或虚高到 90% 以上。 */
    private int ocrProgressPercent(List<MaterialPageEntity> pages) {
        if (pages == null || pages.isEmpty()) {
            return 0;
        }
        long done = pages.stream()
            .filter(page -> page.getOcrStatus() == MaterialOcrStatus.READY || page.getOcrStatus() == MaterialOcrStatus.FAILED)
            .count();
        if (done <= 0) {
            return 1;
        }
        return Math.min(99, (int) Math.floor(done * 100.0 / pages.size()));
    }

    /**
     * 文本抽取阶段已经完成的轻量任务只更新任务可观测状态，不重复处理大文件。
     */
    /**
     * 继续补齐大 PDF 首批之后的文本页。
     *
     * <p>首批导入只保证前若干页尽快可问答；剩余页优先使用 Poppler 的 pdftotext 分批抽取，
     * 本机未安装 Poppler 时退回 PDFBox 按页段抽取。如果页面没有文本层，则按页创建图片型
     * PDF 占位片段，保证阅读器和资料列表能覆盖完整页数。</p>
     */
    private void runRemainingTextExtractionPipeline(Long materialId, Long jobId) {
        LearningMaterialEntity material = learningMaterialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        if (material.getSourceType() != MaterialSourceType.PDF || !isLargePdfFastImport(sourcePath)) {
            material.setTextStatus(MaterialTextStatus.READY);
            learningMaterialRepository.save(material);
            return;
        }
        if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
            throw new BusinessException(404, "Original material file is missing. Please upload this material again.");
        }
        int totalPages = material.getPageCount() == null || material.getPageCount() <= 0
            ? readPdfPageCount(sourcePath).orElse(0)
            : material.getPageCount();
        if (totalPages <= 0) {
            material.setTextStatus(MaterialTextStatus.READY);
            material.setProcessingStage("文本补齐完成");
            material.setProcessingMessage("未能读取剩余页数，已保留当前可用文本索引");
            learningMaterialRepository.save(material);
            return;
        }
        int processedPage = materialPageRepository.findByMaterialIdOrderByPageNoAsc(materialId)
            .stream()
            .map(MaterialPageEntity::getPageNo)
            .filter(Objects::nonNull)
            .max(Integer::compareTo)
            .orElse(Math.min(largePdfFastImportMaxTextPages, totalPages));
        int startPage = processedPage + 1;
        if (startPage > totalPages) {
            markLargePdfRemainingReady(material, totalPages);
            return;
        }

        int endPage = Math.min(totalPages, startPage + 49);
        material.setTextStatus(MaterialTextStatus.RUNNING);
        material.setIndexStatus(MaterialIndexStatus.PARTIAL);
        material.setProcessingProgressPercent(Math.min(95, 85 + (int) Math.floor((startPage * 10.0) / totalPages)));
        material.setProcessingStage("补齐剩余页面");
        material.setProcessingMessage("正在抽取大 PDF 第 " + startPage + "-" + endPage + "/" + totalPages + " 页文本");
        learningMaterialRepository.saveAndFlush(material);

        String extractedText = readLargePdfTextRange(sourcePath, startPage, endPage).orElse("");
        if (shouldAbortProcessingJob(jobId, materialId)) {
            return;
        }
        List<ParsedBlock> blocks = new ArrayList<>(largePdfTextBlocks(extractedText, totalPages, startPage, endPage));
        if (blocks.isEmpty()) {
            blocks.addAll(scannedPdfPagePlaceholderBlocks(totalPages, startPage, endPage));
        }
        ParsedMaterial parsed = new ParsedMaterial(blocks, List.of(), totalPages);
        boolean imagePlaceholderOnly = isImagePlaceholderOnly(parsed);
        List<ChunkDraft> chunks = chunkMaterial(parsed);
        int chunkOffset = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId).size();
        saveChunks(material, chunks, false, false, chunkOffset);
        saveMaterialPages(material, parsed);

        int indexedChunks = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId).size();
        material.setChunkCount(indexedChunks);
        material.setIndexedChunkCount(indexedChunks);
        material.setTextPageCount(endPage);
        material.setTextStatus(endPage >= totalPages && !imagePlaceholderOnly ? MaterialTextStatus.READY : MaterialTextStatus.PARTIAL);
        material.setIndexStatus(MaterialIndexStatus.PARTIAL);
        material.setOcrStatus(resolveOcrStatus(parsed, imagePlaceholderOnly));
        material.setProcessingProgressPercent(endPage >= totalPages ? 95 : Math.min(94, 85 + (int) Math.floor((endPage * 10.0) / totalPages)));
        material.setProcessingStage(imagePlaceholderOnly ? "图片页已入库" : (endPage >= totalPages ? "文本补齐完成" : "部分文本可用"));
        material.setProcessingMessage(imagePlaceholderOnly
            ? "第 " + startPage + "-" + endPage + " 页未识别到可抽取文字，已保留原页图片占位"
            : endPage >= totalPages
            ? "大 PDF 全部页面文本已补齐，向量索引继续后台同步"
            : "大 PDF 已补齐到第 " + endPage + "/" + totalPages + " 页，后续页面继续后台处理");
        learningMaterialRepository.save(material);

        if (imagePlaceholderOnly && ocrEnabled) {
            materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.OCR_PAGE_BATCH, 18, "OCR 识别", "继续分批 OCR 图片型 PDF 页面");
        }
        if (endPage < totalPages) {
            materialProcessingJobService.enqueue(material.getId(), MaterialProcessingJobType.EXTRACT_TEXT_REMAINING, 15, "补齐剩余页面", "继续抽取大 PDF 后续页面文本");
        }
    }

    private boolean isPartialLargePdfImport(LearningMaterialEntity material, Path sourcePath, ParsedMaterial parsed) {
        return material != null
            && material.getSourceType() == MaterialSourceType.PDF
            && isLargePdfFastImport(sourcePath)
            && parsed != null
            && parsed.pageCount() != null
            && parsed.pageCount() > largePdfFastImportMaxTextPages
            && parsedPageCoverage(parsed) < parsed.pageCount();
    }

    private int parsedPageCoverage(ParsedMaterial parsed) {
        if (parsed == null || parsed.blocks() == null) {
            return 0;
        }
        return (int) parsed.blocks().stream()
            .map(ParsedBlock::pageNo)
            .filter(pageNo -> pageNo != null && pageNo > 0)
            .distinct()
            .count();
    }

    private void markLargePdfRemainingReady(LearningMaterialEntity material, int totalPages) {
        if (material.getTextStatus() != MaterialTextStatus.PARTIAL) {
            material.setTextStatus(MaterialTextStatus.READY);
        }
        material.setTextPageCount(totalPages);
        material.setProcessingProgressPercent(Math.max(95, nullToZero(material.getProcessingProgressPercent())));
        material.setProcessingStage(material.getTextStatus() == MaterialTextStatus.PARTIAL ? "图片页已入库" : "文本补齐完成");
        material.setProcessingMessage(material.getTextStatus() == MaterialTextStatus.PARTIAL
            ? "大 PDF 全部页面已入库，但部分页面只有图片占位，未识别到可检索正文"
            : "大 PDF 全部页面文本已补齐，向量索引继续后台同步");
        learningMaterialRepository.save(material);
    }

    /**
     * 判断解析结果是否只有图片页占位文本。
     *
     * <p>图片型 PDF 在没有文本层、OCR 又未产出文字时，也会按页生成
     * {@code [[material-image:page-N.png]]} 占位片段，保证阅读器和来源定位不丢页。
     * 这种结果不能被标记成“文本/OCR 全部完成”，否则用户会误以为已经有可检索正文。</p>
     */
    private boolean isImagePlaceholderOnly(ParsedMaterial parsed) {
        if (parsed == null || parsed.blocks() == null || parsed.blocks().isEmpty()) {
            return false;
        }
        boolean allBlocksArePlaceholders = parsed.blocks().stream()
            .filter(block -> block != null && block.text() != null && !block.text().isBlank())
            .allMatch(block -> isImagePlaceholderText(block.text()));
        boolean hasTextLayer = parsed.textLayers() != null && parsed.textLayers().stream()
            .anyMatch(layer -> layer.text() != null && !layer.text().isBlank());
        return allBlocksArePlaceholders && !hasTextLayer;
    }

    /**
     * 判断单段文本是否只是图片页占位说明。
     *
     * <p>OCR 成功的扫描页同样会包含图片标记，但还会包含实际 OCR 文本；
     * 因此这里会先移除图片标记，再用“暂无可抽取文本”等提示语识别真正的占位内容。</p>
     */
    private boolean isImagePlaceholderText(String text) {
        if (text == null || text.isBlank() || !text.contains(IMAGE_MARKER_PREFIX)) {
            return false;
        }
        String withoutImageMarker = text
            .replaceAll("\\[\\[material-image:[^\\]]+\\]\\]\\s*", "")
            .trim();
        return withoutImageMarker.contains("暂无可抽取文本")
            || withoutImageMarker.contains("未识别到可抽取文字")
            || withoutImageMarker.contains("只有图片占位");
    }

    /**
     * 根据解析结果推导 OCR 状态。
     *
     * <p>只生成图片占位时，如果 OCR 功能本身关闭，则显示跳过；如果 OCR 功能打开但仍没有正文，
     * 说明 OCR 没能完成有效识别，应显示失败/需处理，而不是显示“完成”。</p>
     */
    private MaterialOcrStatus resolveOcrStatus(ParsedMaterial parsed, boolean imagePlaceholderOnly) {
        if (!ocrEnabled) {
            return MaterialOcrStatus.DISABLED;
        }
        if (imagePlaceholderOnly) {
            return MaterialOcrStatus.PENDING;
        }
        return MaterialOcrStatus.READY;
    }

    /** 返回资料卡片上展示的文本抽取阶段。 */
    private String resolveTextExtractionStage(boolean partialLargePdfImport, boolean imagePlaceholderOnly) {
        if (imagePlaceholderOnly) {
            return "图片页已入库";
        }
        return partialLargePdfImport ? "部分文本可用" : "处理完成";
    }

    /** 返回资料卡片上展示的文本抽取说明，避免图片型 PDF 被误描述为正文已完成。 */
    private String resolveTextExtractionMessage(
        LearningMaterialEntity material,
        ParsedMaterial parsed,
        boolean partialLargePdfImport,
        boolean imagePlaceholderOnly
    ) {
        if (imagePlaceholderOnly) {
            return "PDF 页面已按页入库，但未识别到可检索正文；阅读器可查看原页，问答质量取决于后续 OCR 或多模态能力";
        }
        if (partialLargePdfImport) {
            return "大 PDF 前 " + material.getTextPageCount() + "/" + parsed.pageCount() + " 页已可问答，剩余页面正在后台补齐";
        }
        return "资料已经可以用于阅读和问答，向量增强正在后台补齐";
    }

    private void markAlreadyHandledPipelineStep(Long materialId, MaterialProcessingJobType jobType) {
        learningMaterialRepository.findById(materialId).ifPresent(material -> {
            if (jobType == MaterialProcessingJobType.BUILD_BM25
                && (material.getIndexStatus() == MaterialIndexStatus.PENDING || material.getIndexStatus() == MaterialIndexStatus.PARTIAL)) {
                boolean textReady = material.getTextStatus() == MaterialTextStatus.READY;
                boolean ocrBackfillInProgress = isOcrBackfillInProgress(material);
                material.setIndexStatus(textReady ? MaterialIndexStatus.READY : MaterialIndexStatus.PARTIAL);
                if (!ocrBackfillInProgress) {
                    material.setProcessingProgressPercent(textReady ? 100 : Math.max(85, nullToZero(material.getProcessingProgressPercent())));
                    material.setProcessingStage(textReady ? "处理完成" : "基础索引可用");
                    material.setProcessingMessage(textReady
                        ? "资料已经可以用于阅读和问答，向量增强正在后台补齐"
                        : "BM25 已可用，剩余文本或向量增强继续后台补齐");
                }
                learningMaterialRepository.save(material);
            }
        });
    }

    /**
     * 单独重建预览元数据。转换失败只降级预览，不影响文本阅读和问答。
     */
    private void runPreviewPipeline(Long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        if (!Files.exists(sourcePath) || !Files.isRegularFile(sourcePath)) {
            throw new BusinessException(404, "Original material file is missing. Please upload this material again.");
        }
        material.setProcessingProgressPercent(Math.max(60, nullToZero(material.getProcessingProgressPercent())));
        material.setProcessingStage("重建预览");
        material.setProcessingMessage("正在重建阅读预览");
        learningMaterialRepository.saveAndFlush(material);
        try {
            applyPreviewMetadata(material, sourcePath, material.getSourceType());
            material.setProcessingStage("预览已更新");
            material.setProcessingMessage("阅读预览已重建");
        } catch (Exception exception) {
            material.setPreviewStatus(MaterialPreviewStatus.DEGRADED);
            material.setPreviewError(exception.getMessage() == null ? "preview rebuild failed" : exception.getMessage());
            material.setProcessingStage("预览降级");
            material.setProcessingMessage("预览重建失败，已切换文本阅读模式");
        }
        learningMaterialRepository.save(material);
    }

    /**
     * 补齐 MySQL embedding，并在 Qdrant 开启时同步外部向量库。
     */
    private void runEmbeddingPipeline(Long materialId, MaterialProcessingJobType jobType) {
        LearningMaterialEntity material = learningMaterialRepository.findById(materialId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        List<MaterialChunkEntity> chunks = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId);
        if (chunks.isEmpty()) {
            return;
        }
        // 向量增强不能阻塞资料可用状态：数据库 chunk/BM25 已经能支撑阅读和问答，
        // embedding 只作为后台增强交给独立线程补齐，避免资料卡片长期停在 85%/88%。
        boolean imagePlaceholderOnly = chunks.stream()
            .allMatch(chunk -> isImagePlaceholderText(chunk.getChunkText()));
        boolean textReady = material.getTextStatus() == MaterialTextStatus.READY;
        boolean ocrBackfillInProgress = isOcrBackfillInProgress(material);
        material.setIndexStatus(textReady ? MaterialIndexStatus.READY : MaterialIndexStatus.PARTIAL);
        if (!ocrBackfillInProgress) {
            material.setProcessingProgressPercent(textReady || imagePlaceholderOnly ? 100 : Math.max(85, nullToZero(material.getProcessingProgressPercent())));
            material.setProcessingStage(imagePlaceholderOnly ? "图片页已入库" : (textReady ? "处理完成" : "基础索引可用"));
            material.setProcessingMessage(imagePlaceholderOnly
                ? "PDF 页面已按页入库，但未识别到可检索正文；阅读器可查看原页，问答质量取决于后续 OCR 或多模态能力"
                : textReady
                ? "资料已经可以用于阅读和问答，向量增强正在后台补齐"
                : "当前可用文本已完成基础索引，向量增强继续后台补齐");
        }
        material.setIndexedChunkCount(chunks.size());
        learningMaterialRepository.save(material);

        if (jobType == MaterialProcessingJobType.BUILD_EMBEDDING) {
            scheduleVectorIndexRebuildAfterCommit(material.getOwnerId(), materialId);
        }
    }

    /** OCR 分批回填期间，其他轻量索引任务不能覆盖资料卡片上的 OCR 进度提示。 */
    private boolean isOcrBackfillInProgress(LearningMaterialEntity material) {
        if (material == null || material.getSourceType() != MaterialSourceType.PDF) {
            return false;
        }
        return material.getOcrStatus() == MaterialOcrStatus.PENDING
            || material.getOcrStatus() == MaterialOcrStatus.RUNNING
            || material.getOcrStatus() == MaterialOcrStatus.PARTIAL;
    }

    /** 长耗时解析/OCR 写库前的统一取消检查，避免删除或重新解析后旧任务继续覆盖资料状态。 */
    private boolean shouldAbortProcessingJob(Long jobId, Long materialId) {
        if (materialProcessingJobService.isJobCancelled(jobId)) {
            return true;
        }
        return materialId != null && !learningMaterialRepository.existsById(materialId);
    }

    /**
     * 重新解析前清空新旧状态，保留原文件和资料基础信息。
     */
    private void resetMaterialForTextPipeline(LearningMaterialEntity material) {
        material.setTextStatus(MaterialTextStatus.PENDING);
        material.setIndexStatus(MaterialIndexStatus.PENDING);
        material.setOcrStatus(ocrEnabled ? MaterialOcrStatus.PENDING : MaterialOcrStatus.DISABLED);
        material.setProcessingProgressPercent(0);
        material.setProcessingStage("等待重新解析");
        material.setProcessingMessage("后台文本抽取任务已排队");
        material.setIndexedChunkCount(0);
        material.setTextPageCount(0);
        material.setChunkCount(0);
    }

    /**
     * 保存页级文本，供大文件分批处理和阅读器降级展示使用。
     */
    private void saveMaterialPages(LearningMaterialEntity material, ParsedMaterial parsed) {
        if (material == null || material.getId() == null || parsed == null) {
            return;
        }
        Map<Integer, StringBuilder> textByPage = new LinkedHashMap<>();
        for (ParsedBlock block : parsed.blocks()) {
            int pageNo = block.pageNo() == null || block.pageNo() <= 0 ? 1 : block.pageNo();
            textByPage.computeIfAbsent(pageNo, ignored -> new StringBuilder()).append(block.text()).append("\n\n");
        }
        if (textByPage.isEmpty()) {
            return;
        }
        List<MaterialPageEntity> pages = new ArrayList<>();
        for (Map.Entry<Integer, StringBuilder> entry : textByPage.entrySet()) {
            String text = entry.getValue().toString().trim();
            boolean imagePlaceholderPage = isImagePlaceholderText(text);
            MaterialPageEntity page = new MaterialPageEntity();
            page.setMaterialId(material.getId());
            page.setPageNo(entry.getKey());
            page.setText(text);
            page.setTextStatus(text.isBlank() ? MaterialTextStatus.FAILED : (imagePlaceholderPage ? MaterialTextStatus.PARTIAL : MaterialTextStatus.READY));
            page.setOcrStatus(material.getOcrStatus());
            page.setPreviewStatus(material.getPreviewStatus());
            page.setCharCount(text.length());
            page.setTokenCount(estimateTokenCount(text));
            pages.add(page);
        }
        materialPageRepository.saveAll(pages);
    }

    private int resolveTextPageCount(ParsedMaterial parsed) {
        if (parsed.pageCount() != null && parsed.pageCount() > 0) {
            return parsed.pageCount();
        }
        return (int) parsed.blocks().stream()
            .map(ParsedBlock::pageNo)
            .filter(Objects::nonNull)
            .distinct()
            .count();
    }

    private int estimateTokenCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.length() / 1.8));
    }

    /**
     * 记录资料操作日志（上传、分片上传完成等）。
     *
     * @param userId     操作用户 ID
     * @param action     操作类型（如 "UPLOAD_MATERIAL"、"CREATE_UPLOAD_SESSION"）
     * @param materialId 资料 ID
     * @param title      资料标题
     * @param originalName 原始文件名
     * @param fileSize   文件大小
     */
    private void recordMaterialLog(long userId, String action, Long materialId, String title, String originalName, Long fileSize) {
        UsageRecordEntity record = new UsageRecordEntity();
        record.setUserId(userId);
        record.setAction(action);
        record.setTargetType("MATERIAL");
        record.setTargetId(materialId);
        record.setDetail("title=" + safeText(title)
            + ", originalName=" + safeText(originalName)
            + ", fileSize=" + (fileSize == null ? 0 : fileSize));
        usageRecordRepository.save(record);
    }

    private String safeText(String value) {
        return value == null ? "" : value.replace(',', ' ').trim();
    }

    /**
     * 将知识片段保存到数据库并写入向量数据库。
     *
     * <p>为每个片段执行以下操作：
     * <ol>
     *   <li>创建 MaterialChunkEntity 记录，设置文本、页码、章节标题</li>
     *   <li>构建层级路径（资料标题 > 章节/页码 > 切片编号）</li>
     *   <li>提取摘要（取文本前 180 字符或首个完整句子）</li>
     *   <li>提取关键词（基于词频统计，去停用词，取前 8 个）</li>
     *   <li>生成 Embedding 向量（调用 EmbeddingClient，带缓存）</li>
     *   <li>每积累 VECTOR_UPSERT_BATCH_SIZE(200) 条片段后批量写入向量库</li>
     * </ol>
     *
     * @param material       资料实体
     * @param chunks         知识片段草稿列表
     * @param updateProgress 是否更新解析进度（分片上传模式需要实时更新进度）
     */
    private void saveChunks(
        LearningMaterialEntity material,
        List<ChunkDraft> chunks,
        boolean updateProgress,
        boolean buildVectorsNow
    ) {
        saveChunks(material, chunks, updateProgress, buildVectorsNow, 0);
    }

    private void saveChunks(
        LearningMaterialEntity material,
        List<ChunkDraft> chunks,
        boolean updateProgress,
        boolean buildVectorsNow,
        int chunkIndexOffset
    ) {
        List<MaterialChunkEntity> savedChunks = new ArrayList<>();
        Map<Long, List<Double>> embeddingsByChunkId = new LinkedHashMap<>();
        for (int index = 0; index < chunks.size(); index++) {
            ChunkDraft draft = chunks.get(index);
            int chunkIndex = chunkIndexOffset + index;
            MaterialChunkEntity chunk = new MaterialChunkEntity();
            chunk.setMaterialId(material.getId());
            chunk.setChunkIndex(chunkIndex);
            chunk.setChunkText(draft.text());
            chunk.setPageNo(draft.pageNo());
            chunk.setSourcePageStart(draft.pageNo());
            chunk.setSourcePageEnd(draft.pageNo());
            chunk.setSectionTitle(draft.sectionTitle() == null || draft.sectionTitle().isBlank()
                ? "第" + (index + 1) + "切片"
                : draft.sectionTitle());
            chunk.setHierarchyPath(buildHierarchyPath(material, chunk, chunkIndex));
            chunk.setSummary(buildChunkSummary(draft.text()));
            chunk.setKeywords(buildChunkKeywords(draft.text()));
            // Embedding 先落库为 JSON，随后解析成向量批量写入 Vector Store，便于后续重建索引。
            String embeddingJson = buildVectorsNow ? toEmbeddingJson(draft.text()) : null;
            chunk.setEmbeddingJson(embeddingJson);
            chunk.setCharCount(draft.text() == null ? 0 : draft.text().length());
            chunk.setTokenCount(estimateTokenCount(draft.text()));
            chunk.setEmbeddingStatus(embeddingJson == null ? MaterialIndexStatus.PENDING : MaterialIndexStatus.READY);
            chunk.setIndexStatus(MaterialIndexStatus.READY);
            chunk.setCreatedAt(java.time.LocalDateTime.now());
            MaterialChunkEntity saved = materialChunkRepository.save(chunk);
            savedChunks.add(saved);
            List<Double> embedding = parseEmbeddingJson(embeddingJson);
            if (embedding != null && saved.getId() != null) {
                embeddingsByChunkId.put(saved.getId(), embedding);
            }
            if (buildVectorsNow && savedChunks.size() >= VECTOR_UPSERT_BATCH_SIZE) {
                // 分批 flush 控制内存峰值，并减少一次性向量写入失败的影响范围。
                flushVectorBatch(material, savedChunks, embeddingsByChunkId);
            }
            if (updateProgress && chunks.size() > 0 && (index == chunks.size() - 1 || index % 5 == 0)) {
                int percent = 82 + (int) Math.floor(((index + 1) * 10.0) / chunks.size());
                updateMaterialParseProgress(material.getId(), material.getOwnerId(), Math.min(percent, 92), "保存切片",
                    "正在保存知识片段 " + (index + 1) + "/" + chunks.size());
            }
        }
        if (buildVectorsNow) {
            if (updateProgress && !savedChunks.isEmpty()) {
                updateMaterialParseProgress(material.getId(), material.getOwnerId(), 92, "写入向量库", "正在提交最后一批向量索引");
            }
            flushVectorBatch(material, savedChunks, embeddingsByChunkId);
            return;
        }
        if (updateProgress) {
            updateMaterialParseProgress(material.getId(), material.getOwnerId(), 94, "后台建索引", "知识片段已保存，向量索引将在后台自动补齐");
        }
        savedChunks.clear();
        embeddingsByChunkId.clear();
    }

    /**
     * 将累积的知识片段和向量批量写入向量数据库。
     *
     * <p>写入完成后清空待处理列表，释放内存。
     * 使用分批写入避免超大资料（如数百个片段）一次性占用过多内存。
     *
     * @param material           资料实体
     * @param savedChunks        已保存到数据库的知识片段列表（写入后清空）
     * @param embeddingsByChunkId 片段 ID 到 Embedding 向量的映射（写入后清空）
     */
    /**
     * 判断本次上传是否需要延迟建向量索引。
     *
     * <p>上传主链路只负责让资料尽快可阅读、可问答。所有格式都先保存切片并标记成功，
     * Embedding 和外部向量库写入统一放到后台补齐，避免用户看到进度长期卡在 92%。</p>
     */
    private boolean shouldDeferVectorIndexing(MaterialSourceType sourceType, int chunkCount) {
        return chunkCount > 0;
    }

    /**
     * 在事务提交后异步重建资料向量索引。
     *
     * <p>如果当前没有 Spring 事务，直接提交后台任务；如果有事务，等提交成功后再跑，
     * 防止后台线程读到尚未提交的切片数据。</p>
     */
    private void scheduleVectorIndexRebuildAfterCommit(long ownerId, Long materialId) {
        if (materialId == null) {
            return;
        }
        Runnable task = () -> vectorIndexExecutor.submit(() -> rebuildMaterialVectorIndex(ownerId, materialId));
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    /**
     * 管理员批量重建 Qdrant 向量索引。
     *
     * <p>该方法只调度后台任务，不在请求线程中做 Embedding 或 Qdrant 写入，
     * 避免管理员页面因为历史资料多而长时间等待。已有资料片段会被复用，不会重新 OCR 或重新切片。</p>
     *
     * @param materialId 可选资料 ID；为 null 时处理所有已解析资料
     * @return 已提交的资料任务数量
     */
    @Transactional(readOnly = true)
    public int rebuildVectorIndexesForAdmin(Long materialId) {
        if (!vectorStoreClient.configured()) {
            throw new BusinessException(400, "Qdrant 未启用，请先配置 VECTOR_STORE_ENABLED=true 和 VECTOR_STORE_BASE_URL");
        }

        List<VectorIndexRebuildTarget> targets;
        if (materialId != null) {
            LearningMaterialEntity material = learningMaterialRepository.findById(materialId)
                .orElseThrow(() -> new BusinessException(404, "资料不存在"));
            if (material.getParseStatus() != MaterialParseStatus.SUCCESS || material.getOwnerId() == null) {
                return 0;
            }
            targets = List.of(new VectorIndexRebuildTarget(material.getOwnerId(), material.getId()));
        } else {
            targets = learningMaterialRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .filter(material -> material.getId() != null)
                .filter(material -> material.getOwnerId() != null)
                .filter(material -> material.getParseStatus() == MaterialParseStatus.SUCCESS)
                .map(material -> new VectorIndexRebuildTarget(material.getOwnerId(), material.getId()))
                .toList();
        }

        for (VectorIndexRebuildTarget target : targets) {
            vectorIndexExecutor.submit(() -> rebuildMaterialVectorIndex(target.ownerId(), target.materialId()));
        }
        log.info("Admin submitted vector index rebuild tasks: materialId={}, count={}", materialId, targets.size());
        return targets.size();
    }

    /**
     * 后台补齐资料切片的 Embedding，并重建 Vector Store 数据。
     */
    private void rebuildMaterialVectorIndex(long ownerId, Long materialId) {
        try {
            LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId).orElse(null);
            if (material == null || material.getParseStatus() != MaterialParseStatus.SUCCESS) {
                return;
            }
            List<MaterialChunkEntity> chunks = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId);
            if (chunks.isEmpty()) {
                return;
            }
            vectorStoreClient.deleteMaterial(ownerId, materialId);
            List<MaterialChunkEntity> vectorBatch = new ArrayList<>();
            Map<Long, List<Double>> embeddingsByChunkId = new LinkedHashMap<>();
            for (MaterialChunkEntity chunk : chunks) {
                String embeddingJson = toEmbeddingJson(chunk.getChunkText());
                if (embeddingJson != null && !Objects.equals(embeddingJson, chunk.getEmbeddingJson())) {
                    if (!materialStillAvailableForVectorIndex(ownerId, materialId)) {
                        log.info("Skip background vector index rebuild because material was removed: materialId={}", materialId);
                        return;
                    }
                    chunk.setEmbeddingJson(embeddingJson);
                    try {
                        materialChunkRepository.save(chunk);
                    } catch (ObjectOptimisticLockingFailureException exception) {
                        // 用户可能在后台向量补建期间删除了资料；此时停止补建即可，不应把正常竞态记成告警。
                        log.info("Skip background vector index rebuild because material chunks changed: materialId={}", materialId);
                        return;
                    }
                }
                List<Double> embedding = parseEmbeddingJson(embeddingJson);
                if (embedding != null && chunk.getId() != null) {
                    embeddingsByChunkId.put(chunk.getId(), embedding);
                }
                vectorBatch.add(chunk);
                if (vectorBatch.size() >= VECTOR_UPSERT_BATCH_SIZE) {
                    flushVectorBatch(material, vectorBatch, embeddingsByChunkId);
                }
            }
            flushVectorBatch(material, vectorBatch, embeddingsByChunkId);
            log.info("Material vector index rebuilt in background: materialId={}, chunks={}", materialId, chunks.size());
        } catch (Exception exception) {
            log.warn("Background vector index rebuild failed: materialId={}", materialId, exception);
        }
    }

    private boolean materialStillAvailableForVectorIndex(long ownerId, Long materialId) {
        if (materialId == null) {
            return false;
        }
        return learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .map(material -> material.getParseStatus() == MaterialParseStatus.SUCCESS)
            .orElse(false);
    }

    /** 向量索引重建任务的最小定位信息，避免把 JPA 实体直接传入后台线程。 */
    private record VectorIndexRebuildTarget(long ownerId, Long materialId) {
    }

    private void flushVectorBatch(
        LearningMaterialEntity material,
        List<MaterialChunkEntity> savedChunks,
        Map<Long, List<Double>> embeddingsByChunkId
    ) {
        if (!savedChunks.isEmpty()) {
            vectorStoreClient.upsertChunks(material.getOwnerId(), material, savedChunks, embeddingsByChunkId);
            savedChunks.clear();
            embeddingsByChunkId.clear();
        }
    }

    /**
     * 保存页面文本层。
     *
     * <p>当前内置解析器优先保存 PDF/OCR 文本层；如果没有坐标，则保存整页兜底文本块。
     * 即使 bbox 为空，前端也能在页面内展示兜底文本层，从而取消页面下方重复解析正文。
     */
    private void savePageTextBlocks(LearningMaterialEntity material, ParsedMaterial parsed) {
        if (material == null || material.getId() == null || parsed == null || parsed.blocks() == null) {
            return;
        }
        Map<Integer, List<Long>> chunkIdsByPage = chunksByPage(
            material.getId(),
            material.getPageCount() == null ? 0 : material.getPageCount()
        );
        Map<Integer, Integer> blockIndexByPage = new LinkedHashMap<>();
        List<MaterialPageTextBlockEntity> blocks = new ArrayList<>();
        if (parsed.textLayers() != null && !parsed.textLayers().isEmpty()) {
            for (PageTextLayerDraft draft : parsed.textLayers()) {
                String text = draft.text() == null ? "" : draft.text().trim();
                if (text.isBlank()) {
                    continue;
                }
                int pageNo = draft.pageNo() == null || draft.pageNo() <= 0 ? 1 : draft.pageNo();
                List<Long> pageChunkIds = chunkIdsByPage.getOrDefault(pageNo, List.of());
                appendPageTextBlocks(
                    blocks,
                    blockIndexByPage,
                    material.getId(),
                    pageNo,
                    text,
                    normalizeText(draft.blockType(), "paragraph"),
                    normalizeText(draft.source(), "LEGACY"),
                    pageChunkIds.isEmpty() ? null : pageChunkIds.get(0),
                    draft.pageWidth(),
                    draft.pageHeight(),
                    draft.bboxX(),
                    draft.bboxY(),
                    draft.bboxWidth(),
                    draft.bboxHeight(),
                    draft.confidence()
                );
            }
        }
        if (!blocks.isEmpty()) {
            materialPageTextBlockRepository.saveAll(blocks);
            return;
        }
        for (ParsedBlock parsedBlock : parsed.blocks()) {
            String text = parsedBlock.text() == null ? "" : parsedBlock.text().trim();
            if (text.isBlank()) {
                continue;
            }
            int pageNo = parsedBlock.pageNo() == null || parsedBlock.pageNo() <= 0 ? 1 : parsedBlock.pageNo();
            List<Long> pageChunkIds = chunkIdsByPage.getOrDefault(pageNo, List.of());
            appendPageTextBlocks(
                blocks,
                blockIndexByPage,
                material.getId(),
                pageNo,
                text,
                "paragraph",
                "LEGACY",
                pageChunkIds.isEmpty() ? null : pageChunkIds.get(0),
                null,
                null,
                null,
                null,
                null,
                null,
                null
            );
        }
        if (!blocks.isEmpty()) {
            materialPageTextBlockRepository.saveAll(blocks);
        }
    }

    /**
     * 添加页面文本层块。
     *
     * <p>超长 TXT、Word 页面或 PDF 页面正文不能整块写入数据库，否则容易在保存文本层时失败。
     * 这里按固定字符数拆分，再配合 MEDIUMTEXT 迁移兜底，确保上传流程稳定完成。</p>
     */
    private void appendPageTextBlocks(
        List<MaterialPageTextBlockEntity> blocks,
        Map<Integer, Integer> blockIndexByPage,
        Long materialId,
        int pageNo,
        String text,
        String blockType,
        String source,
        Long chunkId,
        Double pageWidth,
        Double pageHeight,
        Double bboxX,
        Double bboxY,
        Double bboxWidth,
        Double bboxHeight,
        Double confidence
    ) {
        for (String part : splitPageTextBlock(text)) {
            MaterialPageTextBlockEntity block = new MaterialPageTextBlockEntity();
            block.setMaterialId(materialId);
            block.setPageNo(pageNo);
            block.setBlockIndex(blockIndexByPage.merge(pageNo, 1, Integer::sum) - 1);
            block.setText(part);
            block.setBlockType(blockType);
            block.setSource(source);
            block.setChunkId(chunkId);
            block.setPageWidth(pageWidth);
            block.setPageHeight(pageHeight);
            block.setBboxX(bboxX);
            block.setBboxY(bboxY);
            block.setBboxWidth(bboxWidth);
            block.setBboxHeight(bboxHeight);
            block.setConfidence(confidence);
            blocks.add(block);
        }
    }

    private List<String> splitPageTextBlock(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.length() <= PAGE_TEXT_BLOCK_MAX_LENGTH) {
            return List.of(normalized);
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + PAGE_TEXT_BLOCK_MAX_LENGTH);
            if (end < normalized.length()) {
                int boundary = findSoftBoundary(normalized, start, end);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String part = normalized.substring(start, end).trim();
            if (!part.isBlank()) {
                parts.add(part);
            }
            start = end;
        }
        return parts;
    }

    private void updateMaterialParseProgress(MaterialUploadSessionEntity session, int percent, String stage, String message) {
        if (session == null || session.getMaterialId() == null) {
            return;
        }
        updateMaterialParseProgress(session.getMaterialId(), session.getOwnerId(), percent, stage, message);
    }

    private void updateMaterialParseProgress(Long materialId, Long ownerId, int percent, String stage, String message) {
        if (materialId == null || ownerId == null) {
            return;
        }
        learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .ifPresent(material -> {
                material.setProcessingProgressPercent(clampProgress(percent));
                material.setProcessingStage(stage);
                material.setProcessingMessage(message);
                learningMaterialRepository.save(material);
            });
    }

    /**
     * 判断上传会话是否已经和资料记录脱节。
     *
     * <p>用户删除资料后再次上传同一个文件时，前端会生成相同的 clientUploadId。
     * 如果旧 SUCCESS 会话还留在数据库里，系统会直接返回旧会话并跳过分片 POST，
     * 因此创建新会话前必须先确认旧会话关联的资料仍然存在且属于当前用户。</p>
     */
    private boolean isOrphanedUploadSession(MaterialUploadSessionEntity session) {
        if (session.getMaterialId() == null || session.getOwnerId() == null) {
            return true;
        }
        return learningMaterialRepository.findByIdAndOwnerId(session.getMaterialId(), session.getOwnerId()).isEmpty();
    }

    private int clampProgress(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 构建知识片段的层级路径（面包屑导航格式）。
     *
     * <p>格式示例："资料标题 > 第3章 概述 > 切片5" 或 "资料标题 > 第3页 > 切片5"。
     * 总长度不超过 500 字符。
     *
     * @param material       资料实体
     * @param chunk          知识片段实体
     * @param zeroBasedIndex 片片索引（从 0 开始）
     * @return 层级路径字符串
     */
    private String buildHierarchyPath(LearningMaterialEntity material, MaterialChunkEntity chunk, int zeroBasedIndex) {
        List<String> parts = new ArrayList<>();
        if (material.getTitle() != null && !material.getTitle().isBlank()) {
            parts.add(material.getTitle().trim());
        }
        if (chunk.getSectionTitle() != null && !chunk.getSectionTitle().isBlank()) {
            parts.add(chunk.getSectionTitle().trim());
        } else if (chunk.getPageNo() != null) {
            parts.add("第" + chunk.getPageNo() + "页");
        }
        parts.add("切片" + (zeroBasedIndex + 1));
        String path = String.join(" > ", parts);
        return path.length() <= 500 ? path : path.substring(0, 500);
    }

    /**
     * 将资料实体转换为列表响应对象。
     */
    private MaterialResponse toResponse(LearningMaterialEntity material) {
        return new MaterialResponse(
            material.getId(),
            material.getTitle(),
            material.getSourceType().name(),
            material.getOriginalName(),
            material.getSourceUrl(),
            material.getFileSize(),
            material.getParseStatus().name(),
            material.getParseProgressPercent(),
            material.getParseStage(),
            material.getParseMessage(),
            material.getUploadStatus() == null ? MaterialUploadStatus.UPLOADED.name() : material.getUploadStatus().name(),
            material.getTextStatus() == null ? MaterialTextStatus.PENDING.name() : material.getTextStatus().name(),
            material.getIndexStatus() == null ? MaterialIndexStatus.PENDING.name() : material.getIndexStatus().name(),
            material.getOcrStatus() == null ? MaterialOcrStatus.DISABLED.name() : material.getOcrStatus().name(),
            material.getProcessingProgressPercent(),
            material.getProcessingStage(),
            material.getProcessingMessage(),
            material.getIndexedChunkCount(),
            material.getTextPageCount(),
            material.getSummaryStatus().name(),
            material.getPreviewStatus() == null ? MaterialPreviewStatus.NONE.name() : material.getPreviewStatus().name(),
            material.getPreviewError(),
            material.getPageCount(),
            material.getChunkCount(),
            material.getCreatedAt() == null ? null : material.getCreatedAt().format(DATETIME_FORMATTER),
            material.getUpdatedAt() == null ? null : material.getUpdatedAt().format(DATETIME_FORMATTER)
        );
    }

    private MaterialDetailResponse toDetailResponse(LearningMaterialEntity material) {
        return new MaterialDetailResponse(
            material.getId(),
            material.getTitle(),
            material.getSourceType().name(),
            material.getOriginalName(),
            material.getSourceUrl(),
            material.getFileSize(),
            material.getParseStatus().name(),
            material.getParseProgressPercent(),
            material.getParseStage(),
            material.getParseMessage(),
            material.getUploadStatus() == null ? MaterialUploadStatus.UPLOADED.name() : material.getUploadStatus().name(),
            material.getTextStatus() == null ? MaterialTextStatus.PENDING.name() : material.getTextStatus().name(),
            material.getIndexStatus() == null ? MaterialIndexStatus.PENDING.name() : material.getIndexStatus().name(),
            material.getOcrStatus() == null ? MaterialOcrStatus.DISABLED.name() : material.getOcrStatus().name(),
            material.getProcessingProgressPercent(),
            material.getProcessingStage(),
            material.getProcessingMessage(),
            material.getIndexedChunkCount(),
            material.getTextPageCount(),
            material.getSummaryStatus().name(),
            material.getPreviewStatus() == null ? MaterialPreviewStatus.NONE.name() : material.getPreviewStatus().name(),
            material.getPreviewError(),
            material.getPageCount(),
            material.getChunkCount(),
            material.getCreatedAt() == null ? null : material.getCreatedAt().format(DATETIME_FORMATTER),
            material.getUpdatedAt() == null ? null : material.getUpdatedAt().format(DATETIME_FORMATTER)
        );
    }

    private MaterialChunkResponse toChunkResponse(MaterialChunkEntity chunk) {
        return new MaterialChunkResponse(
            chunk.getId(),
            chunk.getMaterialId(),
            chunk.getChunkIndex() == null ? null : chunk.getChunkIndex() + 1,
            chunk.getChunkText(),
            chunk.getPageNo(),
            chunk.getSectionTitle(),
            chunk.getHierarchyPath(),
            chunk.getSummary(),
            chunk.getKeywords(),
            excerpt(chunk.getChunkText()),
            chunk.getCreatedAt() == null ? null : chunk.getCreatedAt().format(DATETIME_FORMATTER)
        );
    }

    private MaterialPageTextBlockResponse toPageTextBlockResponse(MaterialPageTextBlockEntity block) {
        return new MaterialPageTextBlockResponse(
            block.getId(),
            block.getPageNo(),
            block.getBlockIndex(),
            block.getText(),
            block.getBlockType(),
            block.getSource(),
            block.getChunkId(),
            block.getPageWidth(),
            block.getPageHeight(),
            block.getBboxX(),
            block.getBboxY(),
            block.getBboxWidth(),
            block.getBboxHeight(),
            block.getConfidence()
        );
    }

    private MaterialUploadSessionResponse toUploadSessionResponse(MaterialUploadSessionEntity session) {
        int uploadedChunks = session.getUploadedChunks() == null ? countUploadedParts(session) : session.getUploadedChunks();
        LearningMaterialEntity material = session.getMaterialId() == null
            ? null
            : learningMaterialRepository.findByIdAndOwnerId(session.getMaterialId(), session.getOwnerId()).orElse(null);
        return new MaterialUploadSessionResponse(
            session.getSessionId(),
            session.getClientUploadId(),
            session.getMaterialId(),
            session.getTitle(),
            session.getOriginalName(),
            session.getSourceType() == null ? null : session.getSourceType().name(),
            session.getSourceUrl(),
            session.getFileSize(),
            session.getChunkSize(),
            session.getTotalChunks(),
            uploadedChunks,
            uploadedPartIndexes(session),
            session.getStatus() == null ? null : session.getStatus().name(),
            session.getErrorMessage(),
            material == null ? null : material.getParseProgressPercent(),
            material == null ? null : material.getParseStage(),
            material == null ? null : material.getParseMessage(),
            material == null || material.getUploadStatus() == null ? null : material.getUploadStatus().name(),
            material == null || material.getTextStatus() == null ? null : material.getTextStatus().name(),
            material == null || material.getIndexStatus() == null ? null : material.getIndexStatus().name(),
            material == null || material.getOcrStatus() == null ? null : material.getOcrStatus().name(),
            material == null ? null : material.getProcessingProgressPercent(),
            material == null ? null : material.getProcessingStage(),
            material == null ? null : material.getProcessingMessage(),
            material == null ? null : material.getIndexedChunkCount(),
            material == null ? null : material.getTextPageCount(),
            session.getCreatedAt() == null ? null : session.getCreatedAt().format(DATETIME_FORMATTER),
            session.getUpdatedAt() == null ? null : session.getUpdatedAt().format(DATETIME_FORMATTER)
        );
    }

    /**
     * 生成文本摘要（最多 160 字符，超出时截断并添加 "..."）。
     * 合并连续空白为单个空格。
     *
     * @param text 原始文本
     * @return 摘要文本
     */
    private String excerpt(String text) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160) + "...";
    }

    /**
     * 解析资料来源类型。
     *
     * <p>如果显式指定了 sourceTypeValue，直接解析枚举值；
     * 否则根据文件扩展名自动推断：.md -> MD、.txt -> TXT、.html/.htm -> HTML、
     * .doc/.docx -> WORD/DOCX、.pptx -> PPTX、.pdf -> PDF，其他默认为 TXT。
     *
     * @param sourceTypeValue 来源类型字符串（可选）
     * @param originalName    原始文件名（用于推断类型）
     * @return 资料来源类型枚举值
     * @throws BusinessException 指定了不支持的类型时抛出 400
     */
    private MaterialSourceType parseSourceType(String sourceTypeValue, String originalName) {
        if (sourceTypeValue != null && !sourceTypeValue.isBlank()) {
            try {
                return MaterialSourceType.valueOf(sourceTypeValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(400, "unsupported material type");
            }
        }
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".md")) {
            return MaterialSourceType.MD;
        }
        if (lowerName.endsWith(".txt")) {
            return MaterialSourceType.TXT;
        }
        if (lowerName.endsWith(".html") || lowerName.endsWith(".htm")) {
            return MaterialSourceType.HTML;
        }
        if (lowerName.endsWith(".doc")) {
            return MaterialSourceType.WORD;
        }
        if (lowerName.endsWith(".docx")) {
            return MaterialSourceType.DOCX;
        }
        if (lowerName.endsWith(".pptx")) {
            return MaterialSourceType.PPTX;
        }
        if (lowerName.endsWith(".xlsx")) {
            return MaterialSourceType.XLSX;
        }
        if (lowerName.endsWith(".pdf")) {
            return MaterialSourceType.PDF;
        }
        return MaterialSourceType.TXT;
    }

    /**
     * 生成文件存储路径。
     *
     * <p>路径结构：{storageRoot}/{ownerId}/{UUID}_{safeFileName}
     * UUID 前缀确保文件名不会冲突，同时保留原始文件名供人识别。
     *
     * @param ownerId      用户 ID
     * @param originalName 原始文件名（非法字符会被替换为下划线）
     * @return 文件存储绝对路径
     */
    private Path resolveStoragePath(long ownerId, String originalName) {
        String safeName = originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return storageRoot.resolve(String.valueOf(ownerId)).resolve(UUID.randomUUID() + "_" + safeName);
    }

    /**
     * 生成临时资料的文件存储路径。
     *
     * <p>路径结构：{storageRoot}/{ownerId}/_temporary/{UUID}_{safeFileName}
     * 使用 _temporary 子目录隔离临时文件，便于清理。
     *
     * @param ownerId      用户 ID
     * @param originalName 原始文件名
     * @return 临时文件存储绝对路径
     */
    private Path resolveTemporaryStoragePath(long ownerId, String originalName) {
        String safeName = originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return storageRoot.resolve(String.valueOf(ownerId)).resolve("_temporary").resolve(UUID.randomUUID() + "_" + safeName);
    }

    /**
     * 解析文档转换器（LibreOffice/soffice）的命令路径。
     *
     * <p>在 Windows 环境下自动探测常见安装路径：
     * C:\Program Files\LibreOffice\program\soffice.com / .exe
     * C:\Program Files (x86)\LibreOffice\program\soffice.com / .exe
     *
     * @param configuredCommand 配置中指定的转换器命令
     * @return 实际可用的转换器命令路径
     */
    private String resolveConverterCommand(String configuredCommand) {
        if (configuredCommand != null && !configuredCommand.isBlank() && !"soffice".equalsIgnoreCase(configuredCommand.trim())) {
            return configuredCommand.trim();
        }
        List<String> candidates = List.of(
            "C:\\Program Files\\LibreOffice\\program\\soffice.com",
            "C:\\Program Files\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.com",
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe"
        );
        for (String candidate : candidates) {
            if (Files.exists(Path.of(candidate))) {
                return candidate;
            }
        }
        return configuredCommand == null || configuredCommand.isBlank() ? "soffice" : configuredCommand.trim();
    }

    /**
     * 将存储路径字符串解析为绝对路径。
     *
     * <p>处理逻辑：
     * <ul>
     *   <li>相对路径：相对于 storageRoot 解析</li>
     *   <li>绝对路径：先尝试重映射到当前 storageRoot（应对存储目录迁移场景），
     *       再检查是否在 storageRoot 范围内</li>
     * </ul>
     *
     * @param storagePath 数据库中存储的路径字符串（相对路径或绝对路径）
     * @return 解析后的绝对路径
     */
    private Path resolveStoredPath(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return storageRoot;
        }
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

    /**
     * 将绝对路径转换为存储路径字符串。
     *
     * <p>如果路径在 storageRoot 内部，则存储为相对路径（便携性更好）；
     * 否则存储为绝对路径。路径分隔符统一使用 "/"。
     *
     * @param storagePath 文件的绝对路径
     * @return 存储到数据库的路径字符串
     */
    private String storagePathValue(Path storagePath) {
        Path absolutePath = storagePath.toAbsolutePath().normalize();
        if (absolutePath.startsWith(storageRoot)) {
            return storageRoot.relativize(absolutePath).toString().replace('\\', '/');
        }
        return absolutePath.toString();
    }

    /**
     * 获取上传会话的分片临时目录路径。
     * 路径格式：{原始文件存储路径}.parts/
     */
    private Path partDir(MaterialUploadSessionEntity session) {
        return resolveStoredPath(session.getStoragePath() + PART_SUFFIX);
    }

    private void ensureUploadPartsDir(MaterialUploadSessionEntity session) {
        try {
            Files.createDirectories(partDir(session));
        } catch (IOException exception) {
            throw new BusinessException(500, "failed to create upload directory");
        }
    }

    /**
     * 清理上传会话的分片临时目录。
     * 先删除目录中的所有 .part 文件，再删除目录本身。
     */
    private void cleanupPartDir(MaterialUploadSessionEntity session) {
        Path dir = partDir(session);
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // ignore
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
        try {
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // ignore
        }
    }

    private Path partPath(MaterialUploadSessionEntity session, int chunkIndex) {
        return partDir(session).resolve(partFileName(chunkIndex));
    }

    private String partFileName(int chunkIndex) {
        return String.format(Locale.ROOT, "%06d.part", chunkIndex);
    }

    /**
     * 计算总分片数：向上取整（fileSize / chunkSize），最小为 1。
     *
     * @param fileSize  文件大小（字节）
     * @param chunkSize 分片大小（字节）
     * @return 总分片数
     * @throws BusinessException chunkSize <= 0 时抛出 400
     */
    private int calculateTotalChunks(long fileSize, int chunkSize) {
        if (chunkSize <= 0) {
            throw new BusinessException(400, "chunkSize must be positive");
        }
        long total = Math.max(1L, (fileSize + chunkSize - 1) / chunkSize);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * 校验文件大小是否在允许范围内。
     *
     * @param fileSize 文件大小（字节）
     * @throws BusinessException 文件大小为负数或超限时抛出 400
     */
    private void validateMaterialFileSize(long fileSize) {
        if (fileSize < 0) {
            throw new BusinessException(400, "fileSize cannot be negative");
        }
        if (fileSize > maxMaterialBytes) {
            throw new BusinessException(400, "file is too large");
        }
    }

    /**
     * 校验合并后文件的 SHA-256 完整性（如果上传会话中配置了校验值）。
     *
     * @param session   上传会话实体
     * @param finalPath 合并后的文件路径
     * @throws IOException 文件读取失败时抛出
     * @throws BusinessException 校验值不匹配时抛出 400
     */
    private void validateUploadedFileChecksum(MaterialUploadSessionEntity session, Path finalPath) throws IOException {
        String expectedChecksum = normalizeOptionalText(session.getChecksumSha256());
        if (expectedChecksum == null) {
            return;
        }
        String actualChecksum = sha256(finalPath);
        if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
            throw new BusinessException(400, "file checksum mismatch");
        }
    }

    /**
     * 拒绝当前仍无法稳定解析的旧版 Office 格式。
     * 旧版 Word (.doc) 会先交给 LibreOffice 转 PDF 后解析；旧版 PPT 暂不放开。
     *
     * @param originalName 原始文件名
     * @throws BusinessException 文件为旧版格式时抛出 400
     */
    private void rejectUnsupportedLegacyOfficeFile(String originalName) {
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".ppt")) {
            throw new BusinessException(400, "旧版 .ppt 文件暂不支持，请另存为 .pptx 后上传");
        }
    }

    /**
     * 查询指定页面的文本层块。
     *
     * <p>页面文本层用于阅读器原位划词。内置解析器只有页级文本时也会返回
     * 一个整页兜底块，保证前端不再需要在页面下方展示重复解析正文。
     */
    @Transactional(readOnly = true)
    public List<MaterialPageTextBlockResponse> pageTextLayer(long ownerId, long materialId, int pageNo) {
        if (pageNo <= 0) {
            throw new BusinessException(400, "Invalid page number");
        }
        learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        return materialPageTextBlockRepository.findByMaterialIdAndPageNoOrderByBlockIndexAsc(materialId, pageNo)
            .stream()
            .map(this::toPageTextBlockResponse)
            .toList();
    }

    /**
     * 解析资料文件，提取文本内容（不带进度回调的简化版本）。
     *
     * @param sourceType 资料来源类型（决定使用哪个解析器）
     * @param storedFile 文件存储路径
     * @return 解析结果（包含多个文本块，每个块关联页码和章节标题）
     * @throws BusinessException 解析失败时抛出 400
     */
    private ParsedMaterial parseMaterial(MaterialSourceType sourceType, Path storedFile) {
        return parseMaterial(sourceType, storedFile, null);
    }

    /**
     * 解析资料文件，提取文本内容（带进度回调）。
     *
     * <p>根据文件类型分发到不同的解析器：
     * <ul>
     *   <li><b>TXT / MD / HTML / WEB</b>：直接读取文本文件，自动检测编码（UTF-8、UTF-16、GB18030）</li>
     *   <li><b>DOCX / WORD</b>：解析 ZIP 结构提取段落、表格和嵌入图片</li>
     *   <li><b>PPTX / PPT</b>：解析 ZIP 结构提取每页幻灯片的文本和图片</li>
     *   <li><b>PDF</b>：使用 PDFBox 逐页提取文本，扫描件支持可选 OCR</li>
     * </ul>
     *
     * @param sourceType       资料来源类型
     * @param storedFile       文件存储路径
     * @param progressListener 进度回调（可选，用于更新解析进度到数据库）
     * @return 解析结果（包含多个文本块）
     * @throws BusinessException 解析失败时抛出 400
     */
    private ParsedMaterial parseMaterial(MaterialSourceType sourceType, Path storedFile, ParseProgressListener progressListener) {
        try {
            if (sourceType == MaterialSourceType.PDF
                || sourceType == MaterialSourceType.DOCX
                || sourceType == MaterialSourceType.WORD
                || sourceType == MaterialSourceType.PPTX
                || sourceType == MaterialSourceType.PPT
                || sourceType == MaterialSourceType.XLSX) {
                // 重新解析前清掉旧图片资源，避免页面图和 Office 内嵌图残留到新解析结果。
                deleteStoredAssets(storedFile.toString());
            }
            return switch (sourceType) {
                case TXT, MD, HTML, WEB -> ParsedMaterial.single(readTextFile(storedFile));
                case DOCX -> parseWord(storedFile);
                case WORD -> parseLegacyWord(storedFile, progressListener);
                case PPTX, PPT -> parsePowerPoint(storedFile);
                case XLSX -> throw new BusinessException(400, "XLSX 文件暂不支持直接解析，请先另存为 PDF、DOCX 或 TXT 后上传");
                case PDF -> parsePdf(storedFile, preparePdfProcessingFile(storedFile), progressListener);
            };
        } catch (Exception exception) {
            log.warn("Material parsing failed for {} ({})", storedFile, sourceType, exception);
            throw new BusinessException(400, "material parsing failed");
        }
    }

    /**
     * 从临时资料的解析结果中提取全部文本。
     *
     * <p>将所有文本块用双换行连接，用于临时资料的即时问答。
     *
     * @param parsed 解析结果
     * @return 合并后的文本字符串；无内容时返回空字符串
     */
    private String temporaryText(ParsedMaterial parsed) {
        if (parsed == null || parsed.blocks() == null || parsed.blocks().isEmpty()) {
            return "";
        }
        return parsed.blocks().stream()
            .map(ParsedBlock::text)
            .filter(text -> text != null && !text.isBlank())
            .map(String::trim)
            .collect(Collectors.joining("\n\n"))
            .trim();
    }

    private boolean isLargePdfFastImport(Path pdfFile) {
        if (pdfFile == null) {
            return false;
        }
        try {
            return Files.size(pdfFile) >= largePdfFastImportBytes;
        } catch (IOException exception) {
            return false;
        }
    }

    private String truncateProcessOutput(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String normalized = output.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 4000 ? normalized : normalized.substring(normalized.length() - 4000);
    }

    private void reportProgress(ParseProgressListener listener, int percent, String stage, String message) {
        if (listener != null) {
            listener.onProgress(percent, stage, message);
        }
    }

    /**
     * 为资料生成预览元数据（PDF 页数、预览状态等）。
     *
     * <p>根据资料类型的不同处理方式：
     * <ul>
     *   <li><b>PDF</b>：读取 PDF 获取页数，设置预览状态为 READY。如果有压缩版预览则优先使用。</li>
     *   <li><b>DOCX / WORD</b>：尝试通过 LibreOffice 转换为 PDF 预览；转换失败时降级为 DEGRADED 状态。</li>
     *   <li><b>其他类型</b>：设置预览状态为 NONE（不支持页面级预览）。</li>
     * </ul>
     *
     * @param material   资料实体（会被直接修改预览相关字段）
     * @param sourcePath 原始文件路径
     * @param sourceType 资料类型
     */
    private void applyPreviewMetadata(LearningMaterialEntity material, Path sourcePath, MaterialSourceType sourceType) {
        if (sourceType == MaterialSourceType.PDF) {
            Path previewSource = preferredPdfPreviewPath(sourcePath);
            updatePdfPreviewMetadata(material, previewSource, MaterialPreviewStatus.READY, null);
            if (material.getPreviewStatus() == MaterialPreviewStatus.FAILED && !previewSource.equals(sourcePath)) {
                deletePdfPreviewCopy(sourcePath);
                updatePdfPreviewMetadata(material, sourcePath, MaterialPreviewStatus.READY, null);
            }
            return;
        }
        if (sourceType == MaterialSourceType.DOCX || sourceType == MaterialSourceType.WORD) {
            Path converted = convertOfficeToPdf(sourcePath);
            if (converted != null) {
                updatePdfPreviewMetadata(material, converted, MaterialPreviewStatus.READY, null);
                return;
            }
            material.setPreviewStatus(MaterialPreviewStatus.DEGRADED);
            material.setPreviewError("DOCX 预览转换器不可用，已降级为文本解析。请安装 LibreOffice/soffice 后重新解析。");
            material.setPageCount(null);
            return;
        }
        material.setPreviewStatus(MaterialPreviewStatus.NONE);
        material.setPreviewError(null);
        material.setPageCount(null);
    }

    /**
     * 大 PDF 快速导入时直接使用解析阶段已经拿到的页数，避免再次加载整份 PDF。
     */
    private boolean applyLargePdfFastPreviewMetadata(LearningMaterialEntity material, Path sourcePath, ParsedMaterial parsed) {
        if (material == null || material.getSourceType() != MaterialSourceType.PDF || !isLargePdfFastImport(sourcePath)) {
            return false;
        }
        Integer pageCount = parsed == null ? null : parsed.pageCount();
        if (pageCount == null || pageCount <= 0) {
            return false;
        }
        material.setPageCount(pageCount);
        material.setPreviewStatus(MaterialPreviewStatus.READY);
        material.setPreviewError(null);
        return true;
    }

    private void updatePdfPreviewMetadata(
        LearningMaterialEntity material,
        Path pdfPath,
        MaterialPreviewStatus status,
        String error
    ) {
        try (var document = Loader.loadPDF(pdfPath.toFile())) {
            material.setPageCount(document.getNumberOfPages());
            material.setPreviewStatus(status);
            material.setPreviewError(error);
        } catch (IOException exception) {
            material.setPageCount(null);
            material.setPreviewStatus(MaterialPreviewStatus.FAILED);
            material.setPreviewError("无法读取预览 PDF。");
        }
    }

    /**
     * 使用 LibreOffice (soffice) 将 Office 文件转换为 PDF。
     *
     * <p>在 Windows 环境下会自动探测 LibreOffice 安装路径。
     * 转换使用 headless 模式（无 GUI），输出到与源文件相同的目录。
     *
     * @param sourcePath Office 文件路径（DOCX/PPTX）
     * @return 转换后的 PDF 文件路径；转换失败或 LibreOffice 不可用时返回 null
     */
    private Path convertOfficeToPdf(Path sourcePath) {
        if (!converterEnabled) {
            return null;
        }
        Path target = previewPdfPath(sourcePath);
        try {
            Files.deleteIfExists(target);
            Path outputDir = target.getParent();
            Files.createDirectories(outputDir);
            Process process = new ProcessBuilder(
                converterCommand,
                "--headless",
                "--convert-to",
                "pdf:writer_pdf_Export",
                "--outdir",
                outputDir.toString(),
                sourcePath.toString()
            )
                .redirectErrorStream(true)
                .start();
            CompletableFuture<String> outputFuture = readProcessOutputAsync(process);
            boolean finished = process.waitFor(converterTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("Office preview conversion failed for {}: {}", sourcePath, processOutput(outputFuture));
                return null;
            }
            Path generated = outputDir.resolve(stripExtension(sourcePath.getFileName().toString()) + ".pdf");
            if (Files.exists(generated) && Files.isRegularFile(generated)) {
                if (!generated.equals(target)) {
                    Files.move(generated, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return target;
            }
            return null;
        } catch (Exception exception) {
            log.warn("Office preview conversion failed for {}", sourcePath, exception);
            return null;
        }
    }

    private CompletableFuture<String> readProcessOutputAsync(Process process) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException exception) {
                return "";
            }
        }, processOutputExecutor);
    }

    private String processOutput(CompletableFuture<String> outputFuture) {
        try {
            return outputFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            return "";
        }
    }

    /**
     * 读取文本文件内容，自动检测编码。
     *
     * <p>编码检测顺序：
     * <ol>
     *   <li>UTF-8 BOM（0xEF 0xBB 0xBF）</li>
     *   <li>UTF-16 LE BOM（0xFF 0xFE）</li>
     *   <li>UTF-16 BE BOM（0xFE 0xFF）</li>
     *   <li>UTF-8（严格解码验证）</li>
     *   <li>GB18030（兜底，兼容 GBK/GB2312）</li>
     * </ol>
     *
     * @param file 文本文件路径
     * @return 文件内容字符串
     * @throws IOException 文件读取失败时抛出
     */
    private String readTextFile(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return decodeStrict(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException exception) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    private boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xff) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString();
    }

    /**
     * 解析 PDF 文件，逐页提取文本内容。
     *
     * <p>使用 PDFBox 的 PDFTextStripper 逐页提取文本。对于无可抽取文本的页面（扫描件），
     * 根据配置决定是否进行内联 OCR，或仅保留图片标记供多模态问答使用。
     *
     * <p>内联 OCR 的条件：OCR 功能已启用，且文件大小和页数均在限制范围内。
     *
     * @param sourceFile       原始 PDF 文件路径（用于图片资源目录定位）
     * @param pdfFile          实际处理的 PDF 文件路径（可能是压缩后的副本）
     * @param progressListener 进度回调（可选）
     * @return 解析结果，每个文本块关联一个页码
     * @throws IOException 文件读取失败时抛出
     */
    private ParsedMaterial parsePdf(Path sourceFile, Path pdfFile, ParseProgressListener progressListener) throws IOException {
        if (isLargePdfFastImport(sourceFile)) {
            return parseLargePdfFast(sourceFile, progressListener);
        }
        List<ParsedBlock> blocks = new ArrayList<>();
        List<PageTextLayerDraft> textLayers = new ArrayList<>();
        int pageCount = 0;
        try (var document = Loader.loadPDF(pdfFile.toFile())) {
            pageCount = document.getNumberOfPages();
            boolean largeFastImport = isLargePdfFastImport(sourceFile);
            int pagesToExtract = largeFastImport
                ? Math.min(pageCount, largePdfFastImportMaxTextPages)
                : pageCount;
            if (largeFastImport) {
                reportProgress(
                    progressListener,
                    32,
                    "快速导入",
                    "大 PDF 已启用快速导入，先抽取前 " + pagesToExtract + "/" + pageCount + " 页用于问答索引"
                );
            }
            boolean inlinePdfOcrEnabled = shouldInlinePdfOcr(pdfFile, pageCount);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int pageIndex = 0; pageIndex < pagesToExtract; pageIndex++) {
                int pageNo = pageIndex + 1;
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String pageText = stripper.getText(document);
                String extractedText = pageText == null ? "" : pageText.trim();
                String blockText = extractedText.isBlank()
                    ? "第 " + pageNo + " 页暂无可抽取文本，已保留原页预览用于阅读和问答依据。"
                    : extractedText;
                if (extractedText.isBlank()) {
                    ScannedPdfPageResult scannedPage = scannedPdfPageTextLayer(sourceFile, document, pageIndex, pageNo, inlinePdfOcrEnabled);
                    blockText = scannedPage.text();
                    textLayers.addAll(scannedPage.textLayers());
                }
                blocks.add(new ParsedBlock(blockText, pageNo, "Page " + pageNo));
                reportPdfParseProgress(progressListener, pageIndex, pageCount, inlinePdfOcrEnabled && extractedText.isBlank());
            }
            if (pagesToExtract < pageCount) {
                blocks.add(new ParsedBlock(
                    "本 PDF 文件较大，系统已优先完成快速导入：当前问答索引包含前 " + pagesToExtract + "/" + pageCount
                        + " 页文本；完整原文仍可在阅读器中打开查看。",
                    pagesToExtract,
                    "Large PDF fast import"
                ));
                reportProgress(
                    progressListener,
                    50,
                    "快速导入完成",
                    "已完成大 PDF 快速文本抽取，正在保存资料"
                );
            }
        }
        return new ParsedMaterial(blocks, textLayers, pageCount);
    }

    /**
     * 大 PDF 快速解析。
     *
     * <p>300MB 级 PDF 如果继续交给 PDFBox 全量加载，遇到高压缩图片、扫描件或损坏对象时
     * 很容易把后台解析线程长时间卡住。快速导入改用 Poppler 的 {@code pdfinfo/pdftotext}
     * 外部命令并设置超时，只抽前 N 页用于问答索引；失败时也返回轻量占位块，
     * 让资料先完成上传并可在阅读器中打开原文。</p>
     */
    private ParsedMaterial parseLargePdfFast(Path sourceFile, ParseProgressListener progressListener) {
        int maxPages = Math.max(1, largePdfFastImportMaxTextPages);
        reportProgress(progressListener, 32, "快速导入", "大 PDF 已启用轻量解析，正在读取页数");
        Integer pageCount = readPdfPageCount(sourceFile).orElse(null);
        reportProgress(progressListener, 38, "快速导入", "正在抽取前 " + maxPages + " 页文本");
        String extractedText = readLargePdfTextRange(sourceFile, 1, maxPages).orElse("");
        List<ParsedBlock> blocks = largePdfTextBlocks(extractedText, pageCount, maxPages);
        if (blocks.isEmpty()) {
            int placeholderPages = pageCount == null || pageCount <= 0 ? maxPages : pageCount;
            blocks = scannedPdfPagePlaceholderBlocks(pageCount, placeholderPages);
        }
        int resolvedPageCount = pageCount == null || pageCount <= 0
            ? Math.max(1, blocks.stream().map(ParsedBlock::pageNo).filter(Objects::nonNull).max(Integer::compareTo).orElse(1))
            : pageCount;
        if (resolvedPageCount > maxPages && parsedPageCoverage(new ParsedMaterial(blocks, List.of(), resolvedPageCount)) < resolvedPageCount) {
            blocks = new ArrayList<>(blocks);
            blocks.add(new ParsedBlock(
                "本 PDF 文件较大，系统已优先完成快速导入：当前问答索引包含前 " + Math.min(maxPages, resolvedPageCount)
                    + "/" + resolvedPageCount + " 页文本；完整原文仍可在阅读器中打开查看。",
                Math.min(maxPages, resolvedPageCount),
                "Large PDF fast import"
            ));
        }
        reportProgress(progressListener, 50, "快速导入完成", "已完成大 PDF 轻量文本抽取，正在保存资料");
        return new ParsedMaterial(blocks, List.of(), resolvedPageCount);
    }

    /**
     * 读取 PDF 总页数。
     *
     * <p>优先使用 Poppler 的 {@code pdfinfo}，因为它对大 PDF 更轻量；如果本机未安装
     * Poppler 或命令执行失败，则退回 PDFBox 只读取页数。没有这个兜底时，图片型 PDF
     * 会被误判成 1 页，进而只生成 1 个占位片段，阅读器后续页面也无法正常开放。</p>
     */
    private Optional<Integer> readPdfPageCount(Path sourceFile) {
        Optional<Integer> fromPdfInfo = readPdfPageCountWithPdfInfo(sourceFile);
        return fromPdfInfo.isPresent() ? fromPdfInfo : readPdfPageCountWithPdfBox(sourceFile);
    }

    /** 使用 pdfinfo 读取 PDF 页数，避免为页数再次用 PDFBox 加载整份大文件。 */
    private Optional<Integer> readPdfPageCountWithPdfInfo(Path sourceFile) {
        return runProcess(List.of("pdfinfo", sourceFile.toString()), LARGE_PDF_FAST_IMPORT_TIMEOUT)
            .flatMap(output -> {
                Matcher matcher = Pattern.compile("(?im)^\\s*Pages:\\s*(\\d+)\\s*$").matcher(output);
                if (!matcher.find()) {
                    return Optional.empty();
                }
                try {
                    int pages = Integer.parseInt(matcher.group(1));
                    return pages > 0 ? Optional.of(pages) : Optional.empty();
                } catch (NumberFormatException exception) {
                    return Optional.empty();
                }
            });
    }

    /**
     * 使用 PDFBox 读取 PDF 页数的兜底实现。
     *
     * <p>这里只读取文档目录和页树，不做全文文本解析，也不渲染图片；即使比 pdfinfo 重，
     * 也比把扫描 PDF 误处理成单页更可控。</p>
     */
    private Optional<Integer> readPdfPageCountWithPdfBox(Path sourceFile) {
        try (var document = Loader.loadPDF(sourceFile.toFile())) {
            int pages = document.getNumberOfPages();
            return pages > 0 ? Optional.of(pages) : Optional.empty();
        } catch (Exception exception) {
            log.warn("PDFBox failed to read page count for {}", sourceFile, exception);
            return Optional.empty();
        }
    }

    /** 使用 pdftotext 抽取大 PDF 的前几页文本，命令失败或超时时返回 empty。 */
    private Optional<String> readLargePdfTextWithPdftotext(Path sourceFile, int maxPages) {
        return readLargePdfTextWithPdftotext(sourceFile, 1, maxPages);
    }

    private Optional<String> readLargePdfTextWithPdftotext(Path sourceFile, int startPage, int endPage) {
        return runProcess(
            List.of("pdftotext", "-f", String.valueOf(startPage), "-l", String.valueOf(endPage), "-layout", sourceFile.toString(), "-"),
            LARGE_PDF_FAST_IMPORT_TIMEOUT
        )
            .map(String::trim)
            .filter(text -> !text.isBlank());
    }

    private Optional<String> readLargePdfTextRange(Path sourceFile, int startPage, int endPage) {
        Optional<String> fromPoppler = readLargePdfTextWithPdftotext(sourceFile, startPage, endPage);
        return fromPoppler.isPresent() ? fromPoppler : readLargePdfTextWithPdfBox(sourceFile, startPage, endPage);
    }

    private Optional<String> readLargePdfTextWithPdfBox(Path sourceFile, int startPage, int endPage) {
        int safeStartPage = Math.max(1, startPage);
        int safeEndPage = Math.max(safeStartPage, endPage);
        try (var document = Loader.loadPDF(sourceFile.toFile())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 0 || safeStartPage > pageCount) {
                return Optional.empty();
            }
            safeEndPage = Math.min(safeEndPage, pageCount);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            StringBuilder text = new StringBuilder();
            for (int pageNo = safeStartPage; pageNo <= safeEndPage; pageNo += 1) {
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String pageText = stripper.getText(document);
                if (pageText != null && !pageText.isBlank()) {
                    if (!text.isEmpty()) {
                        text.append('\f');
                    }
                    text.append(pageText.trim());
                }
            }
            String result = text.toString().trim();
            return result.isBlank() ? Optional.empty() : Optional.of(result);
        } catch (Exception exception) {
            log.warn("PDFBox failed to extract text range for {} pages {}-{}", sourceFile, safeStartPage, safeEndPage, exception);
            return Optional.empty();
        }
    }

    /** 执行外部命令并读取标准输出，失败时记录日志并返回 empty。 */
    private Optional<String> runProcess(List<String> command, Duration timeout) {
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            CompletableFuture<String> outputFuture = readProcessOutputAsync(process);
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Command timed out: {}", command);
                return Optional.empty();
            }
            String output = processOutput(outputFuture);
            if (process.exitValue() != 0) {
                log.warn("Command failed exitCode={} command={} output={}", process.exitValue(), command, truncateProcessOutput(output));
                return Optional.empty();
            }
            return Optional.ofNullable(output);
        } catch (Exception exception) {
            log.warn("Command unavailable or failed: {}", command, exception);
            return Optional.empty();
        }
    }

    /** 将 pdftotext 的输出按页拆成解析块。 */
    private List<ParsedBlock> largePdfTextBlocks(String text, Integer pageCount, int maxPages) {
        int endPage = pageCount == null || pageCount <= 0 ? maxPages : Math.min(pageCount, maxPages);
        return largePdfTextBlocks(text, pageCount, 1, endPage);
    }

    private List<ParsedBlock> largePdfTextBlocks(String text, Integer pageCount, int startPage, int endPage) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        String[] pages = normalized.split("\\f+");
        List<ParsedBlock> blocks = new ArrayList<>();
        int safeStartPage = Math.max(1, startPage);
        int safeEndPage = Math.max(safeStartPage, endPage);
        if (pageCount != null && pageCount > 0) {
            safeEndPage = Math.min(safeEndPage, pageCount);
        }
        int limit = Math.min(pages.length, safeEndPage - safeStartPage + 1);
        for (int index = 0; index < limit; index++) {
            String pageText = pages[index] == null ? "" : pages[index].trim();
            if (!pageText.isBlank()) {
                int pageNo = safeStartPage + index;
                blocks.add(new ParsedBlock(pageText, pageNo, "Page " + pageNo));
            }
        }
        if (blocks.isEmpty() && !normalized.isBlank()) {
            blocks.add(new ParsedBlock(normalized, safeStartPage, "Page " + safeStartPage));
        }
        return blocks;
    }

    private List<ParsedBlock> scannedPdfPagePlaceholderBlocks(Integer pageCount, int maxPages) {
        int resolvedPageCount = pageCount == null || pageCount <= 0 ? 1 : pageCount;
        int pagesToIndex = Math.max(1, Math.min(resolvedPageCount, Math.max(1, maxPages)));
        return scannedPdfPagePlaceholderBlocks(resolvedPageCount, 1, pagesToIndex);
    }

    private List<ParsedBlock> scannedPdfPagePlaceholderBlocks(Integer pageCount, int startPage, int endPage) {
        int resolvedPageCount = pageCount == null || pageCount <= 0 ? Math.max(1, endPage) : pageCount;
        int safeStartPage = Math.max(1, Math.min(startPage, resolvedPageCount));
        int safeEndPage = Math.max(safeStartPage, Math.min(endPage, resolvedPageCount));
        List<ParsedBlock> blocks = new ArrayList<>();
        for (int pageNo = safeStartPage; pageNo <= safeEndPage; pageNo++) {
            String imageName = pageImageName(pageNo);
            // 图片型 PDF 抽不到文本时仍按页创建片段，保证 RAG 来源能定位到具体页。
            blocks.add(new ParsedBlock(
                imageMarker(imageName) + "\n第 " + pageNo + " 页暂无可抽取文本；该 PDF 可能是扫描件或图片型页面，已按页保留原页用于预览和多模态问答。",
                pageNo,
                "Page " + pageNo
            ));
        }
        return blocks;
    }

    private void reportPdfParseProgress(ParseProgressListener progressListener, int pageIndex, int pageCount, boolean ocrPage) {
        if (progressListener == null || pageCount <= 0) {
            return;
        }
        int pageNo = pageIndex + 1;
        if (pageNo != pageCount && pageNo % 5 != 0) {
            return;
        }
        int percent = 30 + (int) Math.floor((pageNo * 20.0) / pageCount);
        progressListener.onProgress(
            Math.min(50, Math.max(30, percent)),
            ocrPage ? "OCR 识别" : "提取文本",
            "正在解析 PDF 第 " + pageNo + "/" + pageCount + " 页"
        );
    }

    /**
     * 准备 PDF 解析用的文件（可能需要压缩）。
     *
     * <p>如果 PDF 文件大小超过压缩阈值（默认 64MB），使用 Ghostscript 压缩
     * 以减少 PDFBox 解析时的内存占用。压缩后的文件保存为 .preview.pdf 副本。
     *
     * @param sourcePath 原始 PDF 文件路径
     * @return 实际用于解析的 PDF 路径（可能是压缩后的副本）
     */
    private Path preparePdfProcessingFile(Path sourcePath) {
        Path previewPath = previewPdfPath(sourcePath);
        if (!shouldCompressPdf(sourcePath)) {
            deletePdfPreviewCopy(sourcePath);
            return sourcePath;
        }
        Path compressedPath = compressPdf(sourcePath, previewPath);
        if (compressedPath != null) {
            return compressedPath;
        }
        deletePdfPreviewCopy(sourcePath);
        return sourcePath;
    }

    private boolean shouldCompressPdf(Path sourcePath) {
        if (!pdfCompressionEnabled) {
            return false;
        }
        if (isLargePdfFastImport(sourcePath)) {
            log.info("Skip PDF compression for large PDF fast import: {}", sourcePath);
            return false;
        }
        try {
            return Files.size(sourcePath) >= pdfCompressionMinBytes;
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * 使用 Ghostscript 压缩 PDF 文件。
     *
     * <p>压缩策略：降低图片分辨率（到 pdfCompressionTargetDpi），检测并合并重复图片，
     * 压缩字体。压缩后如果文件反而变大，则放弃压缩结果。
     *
     * @param sourcePath 原始 PDF 路径
     * @param targetPath 压缩后输出路径
     * @return 压缩后的文件路径；压缩失败或输出不更小时返回 null
     */
    private Path compressPdf(Path sourcePath, Path targetPath) {
        try {
            Files.deleteIfExists(targetPath);
            Files.createDirectories(targetPath.getParent());
            List<String> command = buildPdfCompressionCommand(sourcePath, targetPath);
            if (command.isEmpty()) {
                return null;
            }
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            CompletableFuture<String> outputFuture = readProcessOutputAsync(process);
            boolean finished = process.waitFor(pdfCompressionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("PDF compression timed out for {}", sourcePath);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("PDF compression failed for {}: {}", sourcePath, processOutput(outputFuture));
                return null;
            }
            if (!Files.exists(targetPath) || !Files.isRegularFile(targetPath)) {
                return null;
            }
            long sourceBytes = Files.size(sourcePath);
            long targetBytes = Files.size(targetPath);
            if (targetBytes <= 0 || targetBytes >= sourceBytes) {
                log.info("PDF compression skipped for {} because output was not smaller ({} -> {})", sourcePath, sourceBytes, targetBytes);
                return null;
            }
            log.info("Compressed PDF preview for {} ({} -> {})", sourcePath, sourceBytes, targetBytes);
            return targetPath;
        } catch (Exception exception) {
            log.warn("PDF compression failed for {}", sourcePath, exception);
            return null;
        }
    }

    private List<String> buildPdfCompressionCommand(Path sourcePath, Path targetPath) {
        if (pdfCompressionCommandTemplate != null && !pdfCompressionCommandTemplate.isBlank()) {
            return tokenizeCommandTemplate(pdfCompressionCommandTemplate)
                .stream()
                .map(part -> part
                    .replace("{input}", sourcePath.toString())
                    .replace("{output}", targetPath.toString())
                    .replace("{dpi}", String.valueOf(pdfCompressionTargetDpi)))
                .filter(part -> !part.isBlank())
                .toList();
        }
        return List.of(
            pdfCompressionCommand,
            "-sDEVICE=pdfwrite",
            "-dCompatibilityLevel=1.6",
            "-dNOPAUSE",
            "-dQUIET",
            "-dBATCH",
            "-dDetectDuplicateImages=true",
            "-dCompressFonts=true",
            "-dDownsampleColorImages=true",
            "-dColorImageDownsampleType=/Bicubic",
            "-dColorImageResolution=" + pdfCompressionTargetDpi,
            "-dDownsampleGrayImages=true",
            "-dGrayImageDownsampleType=/Bicubic",
            "-dGrayImageResolution=" + pdfCompressionTargetDpi,
            "-dDownsampleMonoImages=true",
            "-dMonoImageDownsampleType=/Subsample",
            "-dMonoImageResolution=" + pdfCompressionTargetDpi,
            "-sOutputFile=" + targetPath,
            sourcePath.toString()
        );
    }

    /**
     * 处理 PDF 扫描件页面（无可抽取文本的页面）。
     *
     * <p>处理逻辑：
     * <ul>
     *   <li>始终添加图片标记（[[material-image:page-N.png]]），供前端预览使用</li>
     *   <li>如果内联 OCR 已启用：渲染页面为 PNG 图片，调用 Tesseract 提取文字</li>
     *   <li>如果 OCR 未启用或失败：仅保留图片标记和提示信息</li>
     * </ul>
     *
     * @param file               原始 PDF 文件路径（用于定位资源目录）
     * @param document           已加载的 PDDocument 对象
     * @param pageIndex          页面索引（从 0 开始）
     * @param pageNo             页码（从 1 开始）
     * @param inlinePdfOcrEnabled 是否启用内联 OCR
     * @return 包含图片标记和可能的 OCR 文本的字符串
     */
    private ScannedPdfPageResult scannedPdfPageTextLayer(
        Path file,
        org.apache.pdfbox.pdmodel.PDDocument document,
        int pageIndex,
        int pageNo,
        boolean inlinePdfOcrEnabled
    ) {
        String imageName = pageImageName(pageNo);
        String marker = imageMarker(imageName);
        if (!inlinePdfOcrEnabled) {
            // 扫描页即使不做 OCR 也保留图片标记，后续预览或多模态问答仍能定位原页。
            return new ScannedPdfPageResult(
                marker + "\n第 " + pageNo + " 页暂无可抽取文本；原页图片将在预览或多模态问答时按需生成。",
                List.of()
            );
        }
        Path imagePath = renderPdfPageAsset(file, document, pageIndex, imageName);
        String ocrText = imagePath == null ? "" : ocrImageText(imagePath);
        if (!ocrText.isBlank()) {
            return new ScannedPdfPageResult(
                marker + "\n[image ocr: " + imageName + "]\n" + ocrText,
                scannedPdfOcrLineTextLayers(document, pageIndex, pageNo, ocrText)
            );
        }
        return new ScannedPdfPageResult(
            marker + "\n第 " + pageNo + " 页暂无可抽取文本；已保留原页图片，可用于预览和多模态问答。",
            List.of()
        );
    }

    /**
     * 为 legacy OCR 结果生成页面透明文本层兜底。
     *
     * <p>Tesseract stdout 模式只能稳定返回文本，缺少每个文字的真实坐标；这里按 OCR 行数在页面正文区域内均分，
     * 让扫描 PDF 仍然可以按行划词；这些坐标是兜底估算值，不等同于真实 OCR 字符坐标。
     */
    private List<PageTextLayerDraft> scannedPdfOcrLineTextLayers(
        org.apache.pdfbox.pdmodel.PDDocument document,
        int pageIndex,
        int pageNo,
        String ocrText
    ) {
        List<String> lines = Arrays.stream((ocrText == null ? "" : ocrText).split("\\R+"))
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .toList();
        if (lines.isEmpty()) {
            return List.of();
        }
        PDRectangle pageBox = document.getPage(pageIndex).getCropBox();
        if (pageBox == null) {
            pageBox = document.getPage(pageIndex).getMediaBox();
        }
        double pageWidth = pageBox == null ? DEFAULT_TEXT_LAYER_PAGE_SIZE : Math.max(1.0, pageBox.getWidth());
        double pageHeight = pageBox == null ? DEFAULT_TEXT_LAYER_PAGE_SIZE : Math.max(1.0, pageBox.getHeight());
        double left = pageWidth * 0.06;
        double top = pageHeight * 0.06;
        double width = pageWidth * 0.88;
        double lineHeight = (pageHeight * 0.88) / Math.max(1, lines.size());
        List<PageTextLayerDraft> layers = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            layers.add(new PageTextLayerDraft(
                pageNo,
                index,
                lines.get(index),
                "ocr-line",
                "LEGACY_OCR",
                pageWidth,
                pageHeight,
                left,
                top + index * lineHeight,
                width,
                Math.max(8.0, lineHeight * 0.92),
                null
            ));
        }
        return layers;
    }

    private boolean shouldInlinePdfOcr(Path file, int pageCount) {
        if (!ocrEnabled) {
            return false;
        }
        if (isLargePdfFastImport(file)) {
            log.info("Skip inline OCR for large PDF fast import: {}", file);
            return false;
        }
        if (pageCount > inlinePdfOcrMaxPages) {
            return false;
        }
        try {
            return Files.size(file) <= inlinePdfOcrMaxBytes;
        } catch (IOException exception) {
            return false;
        }
    }

    private List<String> extractPdfImages(PDResources resources, Path sourceFile, String scope, ImageCounter counter) throws IOException {
        List<String> imageBlocks = new ArrayList<>();
        if (resources == null) {
            return imageBlocks;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
            if (xObject instanceof PDImageXObject image) {
                String assetName = saveImageAsset(sourceFile, image.getImage(), scope, counter.next(), "png");
                imageBlocks.add(imageBlock(assetName, assetDir(sourceFile).resolve(assetName)));
            }
            if (xObject instanceof PDFormXObject form) {
                imageBlocks.addAll(extractPdfImages(form.getResources(), sourceFile, scope, counter));
            }
        }
        return imageBlocks;
    }

    /**
     * 解析 Word (DOCX) 文件。
     *
     * <p>DOCX 本质上是一个 ZIP 压缩包，内部包含 XML 格式的内容。
     * 解析步骤：
     * <ol>
     *   <li>打开 ZIP 文件，读取 word/document.xml 中的段落文本</li>
     *   <li>提取表格内容（使用 "[table]" 标记，行间用 "|" 分隔）</li>
     *   <li>提取 word/media/ 目录中的嵌入图片并保存为资源文件</li>
     *   <li>如果文档包含绘图对象但无媒体文件，添加 "[image]" 标记</li>
     * </ol>
     *
     * @param file DOCX 文件路径
     * @return 解析结果（单个文本块）
     * @throws IOException 文件读取失败时抛出
     */
    private ParsedMaterial parseWord(Path file) throws IOException {
        try (var zipFile = new java.util.zip.ZipFile(file.toFile())) {
            StringBuilder builder = new StringBuilder();
            boolean hasDrawing = false;
            List<String> mediaEntries = new ArrayList<>();
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entry.isDirectory() && entryName.startsWith("word/media/")) {
                    mediaEntries.add(entryName);
                }
                if (!entryName.equals("word/document.xml")) {
                    continue;
                }
                String xml = new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                hasDrawing = containsWordDrawing(xml);
                appendWordParagraphs(builder, xml);
                appendWordTables(builder, xml);
            }
            mediaEntries.sort(String::compareTo);
            int imageIndex = 1;
            for (String entryName : mediaEntries) {
                String assetName = saveOfficeImageAsset(zipFile, entryName, file, "word", imageIndex++);
                appendBlock(builder, imageBlock(assetName, assetDir(file).resolve(assetName)));
            }
            if (mediaEntries.isEmpty() && hasDrawing) {
                appendBlock(builder, "[image]");
            }
            return ParsedMaterial.single(builder.toString().trim());
        }
    }

    /**
     * 解析旧版 Word (.doc) 文件。
     *
     * <p>.doc 是二进制 Office 格式，不能像 .docx 一样直接解 ZIP 读取 XML。
     * 优先用 Apache POI 直接抽取正文，保证未安装 LibreOffice 的环境也能上传和问答；
     * 如果 POI 无法识别该文件，再尝试 LibreOffice 转 PDF 后解析。
     */
    private ParsedMaterial parseLegacyWord(Path file, ParseProgressListener progressListener) throws IOException {
        try (var input = Files.newInputStream(file);
             var document = new HWPFDocument(input);
             var extractor = new WordExtractor(document)) {
            String text = normalizeLegacyWordText(extractor.getText());
            if (!text.isBlank()) {
                return ParsedMaterial.single(text);
            }
        } catch (Exception exception) {
            log.warn("Apache POI failed to parse legacy Word file {}, trying LibreOffice fallback", file, exception);
        }
        Path convertedPdf = convertOfficeToPdf(file);
        if (convertedPdf == null) {
            throw new BusinessException(400, "旧版 .doc 文件解析失败，请确认文件未损坏，或先另存为 .docx 再上传");
        }
        return parsePdf(file, convertedPdf, progressListener);
    }

    /**
     * 清理旧版 Word 抽取出的控制字符和多余空行。
     *
     * <p>HWPF 会保留部分 Word 段落控制符，统一替换为换行，避免切片和阅读器看到不可见乱码。
     */
    private String normalizeLegacyWordText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\r', '\n')
            .replace('\u0007', '\n')
            .replaceAll("[\\u0000-\\u0006\\u0008-\\u001F]", "")
            .replaceAll("[ \\t\\x0B\\f]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private boolean containsWordDrawing(String xml) {
        return xml.contains("<w:drawing")
            || xml.contains("<w:pict")
            || xml.contains("<v:shape")
            || xml.contains("<v:imagedata")
            || xml.contains("<a:blip");
    }

    private void appendWordParagraphs(StringBuilder builder, String xml) {
        java.util.regex.Matcher paragraphMatcher = java.util.regex.Pattern
            .compile("(?is)<w:p(?:\\s[^>]*)?>(.*?)</w:p>")
            .matcher(xml);
        while (paragraphMatcher.find()) {
            String paragraph = extractOfficeTextRuns(paragraphMatcher.group(1), "w:t", "");
            appendBlock(builder, paragraph);
        }
    }

    private void appendWordTables(StringBuilder builder, String xml) {
        java.util.regex.Matcher tableMatcher = java.util.regex.Pattern
            .compile("(?is)<w:tbl(?:\\s[^>]*)?>(.*?)</w:tbl>")
            .matcher(xml);
        while (tableMatcher.find()) {
            String tableXml = tableMatcher.group(1);
            List<String> rows = new ArrayList<>();
            java.util.regex.Matcher rowMatcher = java.util.regex.Pattern
                .compile("(?is)<w:tr(?:\\s[^>]*)?>(.*?)</w:tr>")
                .matcher(tableXml);
            while (rowMatcher.find()) {
                String row = extractOfficeTextRuns(rowMatcher.group(1), "w:t", " ");
                if (!row.isBlank()) {
                    rows.add(row.trim());
                }
            }
            if (!rows.isEmpty()) {
                appendBlock(builder, "[table] " + String.join(" | ", rows));
            }
        }
    }

    /**
     * 解析 PowerPoint (PPTX) 文件。
     *
     * <p>PPTX 也是一个 ZIP 压缩包。解析步骤：
     * <ol>
     *   <li>扫描 ZIP 中所有 ppt/slides/slideN.xml 并按页码排序</li>
     *   <li>从每页幻灯片的 XML 中提取文本（a:t 标签）</li>
     *   <li>根据 relationship 文件找到每页关联的图片，提取并保存为资源文件</li>
     *   <li>如果幻灯片包含图片引用但无法提取，添加 "[image]" 标记</li>
     * </ol>
     * 每页幻灯片作为一个独立的 ParsedBlock，页码为幻灯片编号。
     *
     * @param file PPTX 文件路径
     * @return 解析结果（每个幻灯片为一个文本块）
     * @throws IOException 文件读取失败时抛出
     */
    private ParsedMaterial parsePowerPoint(Path file) throws IOException {
        try (var zipFile = new java.util.zip.ZipFile(file.toFile())) {
            List<String> slideEntries = new ArrayList<>();
            var entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.getName().matches("ppt/slides/slide\\d+\\.xml")) {
                    slideEntries.add(entry.getName());
                }
            }
            slideEntries.sort(java.util.Comparator.comparingInt(this::extractSlideNo));
            List<ParsedBlock> blocks = new ArrayList<>();
            for (String entryName : slideEntries) {
                int slideNo = extractSlideNo(entryName);
                var entry = zipFile.getEntry(entryName);
                String xml = new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                String slideText = extractOfficeTextRuns(xml, "a:t", "\n");
                StringBuilder builder = new StringBuilder(slideText);
                List<String> imageEntries = slideImageRelationshipEntries(zipFile, slideNo);
                int imageIndex = 1;
                for (String imageEntry : imageEntries) {
                    String assetName = saveOfficeImageAsset(zipFile, imageEntry, file, "slide-" + slideNo, imageIndex++);
                    appendBlock(builder, imageBlock(assetName, assetDir(file).resolve(assetName)));
                }
                if (imageEntries.isEmpty() && (containsPowerPointImage(xml) || slideHasImageRelationship(zipFile, slideNo))) {
                    appendBlock(builder, "[image]");
                }
                if (!builder.isEmpty()) {
                    blocks.add(new ParsedBlock(builder.toString().trim(), slideNo, "Slide " + slideNo));
                }
            }
            return new ParsedMaterial(blocks);
        }
    }

    private int extractSlideNo(String entryName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("slide(\\d+)\\.xml$")
            .matcher(entryName);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : Integer.MAX_VALUE;
    }

    private boolean containsPowerPointImage(String xml) {
        return xml.contains("<p:pic") || xml.contains("<a:blip");
    }

    private boolean slideHasImageRelationship(java.util.zip.ZipFile zipFile, int slideNo) throws IOException {
        var relsEntry = zipFile.getEntry("ppt/slides/_rels/slide" + slideNo + ".xml.rels");
        if (relsEntry == null) {
            return false;
        }
        String rels = new String(zipFile.getInputStream(relsEntry).readAllBytes(), StandardCharsets.UTF_8);
        return rels.contains("/image") || rels.contains("../media/");
    }

    private List<String> slideImageRelationshipEntries(java.util.zip.ZipFile zipFile, int slideNo) throws IOException {
        var relsEntry = zipFile.getEntry("ppt/slides/_rels/slide" + slideNo + ".xml.rels");
        if (relsEntry == null) {
            return List.of();
        }
        String rels = new String(zipFile.getInputStream(relsEntry).readAllBytes(), StandardCharsets.UTF_8);
        List<String> imageEntries = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?is)<Relationship\\b[^>]*\\bTarget=(['\"])(.*?)\\1[^>]*/?>")
            .matcher(rels);
        while (matcher.find()) {
            String tag = matcher.group(0);
            String target = unescapeXml(matcher.group(2));
            if (!tag.toLowerCase(Locale.ROOT).contains("image") && !target.toLowerCase(Locale.ROOT).contains("/media/")) {
                continue;
            }
            String normalized = normalizeZipEntryName(Path.of("ppt", "slides").resolve(target.replace('\\', '/')).normalize().toString());
            if (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            var imageEntry = zipFile.getEntry(normalized);
            if (imageEntry != null && !imageEntry.isDirectory()) {
                imageEntries.add(normalized);
            }
        }
        imageEntries.sort(String::compareTo);
        return imageEntries;
    }

    private String extractOfficeTextRuns(String xml, String tagName, String separator) {
        StringBuilder builder = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("(?is)<" + tagName + "(?:\\s[^>]*)?>(.*?)</" + tagName + ">")
            .matcher(xml);
        while (matcher.find()) {
            String value = unescapeXml(matcher.group(1));
            if (value.isBlank()) {
                continue;
            }
            if (!builder.isEmpty() && !separator.isEmpty()) {
                builder.append(separator);
            }
            builder.append(value);
        }
        return builder.toString().trim();
    }

    private void appendBlock(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(text.trim());
    }

    private String unescapeXml(String value) {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'");
    }

    /**
     * 将解析结果的所有文本块切分为知识片段。
     *
     * <p>遍历每个 ParsedBlock，对其中的文本调用 chunkText 进行语义分块，
     * 并保留原始的页码和章节标题信息。
     *
     * @param parsed 解析结果
     * @return 知识片段草稿列表（包含文本、页码、章节标题）
     */
    private List<ChunkDraft> chunkMaterial(ParsedMaterial parsed) {
        List<ChunkDraft> chunks = new ArrayList<>();
        for (ParsedBlock block : parsed.blocks()) {
            if (isScannedPdfPageBlock(block)) {
                // 图片型 PDF 的每个页面必须保持为一个完整 chunk，避免 OCR 文本过长时被语义切片拆散。
                String text = cleanTextForChunking(block.text());
                if (!text.isBlank()) {
                    chunks.add(new ChunkDraft(text, block.pageNo(), block.sectionTitle()));
                }
                continue;
            }
            chunks.addAll(chunkBlockBySections(block));
        }
        return chunks;
    }

    /**
     * 将单个解析块先按标题拆成章节，再对每个章节做语义切片。
     *
     * <p>TXT、Markdown、Word 这类没有天然页概念的文件，如果只按固定长度切片，
     * 会把章节标题和正文拆散。这里先识别 Markdown 标题、中文章节标题和编号标题，
     * 再把章节标题写入 chunk 的 sectionTitle，方便来源定位和阅读器展示。</p>
     */
    private List<ChunkDraft> chunkBlockBySections(ParsedBlock block) {
        String normalized = cleanTextForChunking(block == null ? null : block.text());
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.contains(IMAGE_MARKER_PREFIX)) {
            return chunkTextWithImageMarkers(normalized)
                .stream()
                .map(text -> new ChunkDraft(text, block.pageNo(), block.sectionTitle()))
                .toList();
        }
        List<ChunkDraft> chunks = new ArrayList<>();
        for (TextSection section : splitTextSections(normalized, block.sectionTitle())) {
            for (String text : chunkText(section.text())) {
                chunks.add(new ChunkDraft(text, block.pageNo(), section.title()));
            }
        }
        return chunks;
    }

    private boolean isScannedPdfPageBlock(ParsedBlock block) {
        return block != null
            && block.pageNo() != null
            && block.text() != null
            && block.text().contains(IMAGE_MARKER_PREFIX);
    }

    /**
     * 清洗进入切片流程的文本。
     *
     * <p>解析器可能输出 BOM、零宽字符、不可见控制符、全角空格和过多空行。
     * 这些字符会影响标题识别、检索命中和阅读器排版；清洗时保留换行结构，避免把段落压成一行。</p>
     */
    private String cleanTextForChunking(String content) {
        String normalized = content == null ? "" : content;
        normalized = normalized
            .replace("\uFEFF", "")
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n');
        normalized = normalized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        normalized = normalized.replaceAll("[\\u200B\\u200C\\u200D\\u2060]", "");
        normalized = normalized.replaceAll("[ \\t]+\\n", "\n");
        normalized = normalized.replaceAll("\\n[ \\t]+", "\n");
        normalized = normalized.replaceAll("[ \\t]{2,}", " ");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private List<TextSection> splitTextSections(String text, String fallbackTitle) {
        String normalized = cleanTextForChunking(text);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<TextSection> sections = new ArrayList<>();
        String currentTitle = normalizeSectionTitle(fallbackTitle);
        StringBuilder buffer = new StringBuilder();
        for (String line : normalized.split("\\n", -1)) {
            if (isHeadingLine(line)) {
                appendTextSection(sections, currentTitle, buffer);
                currentTitle = normalizeHeadingTitle(line);
                buffer.setLength(0);
                buffer.append(currentTitle).append('\n');
                continue;
            }
            buffer.append(line).append('\n');
        }
        appendTextSection(sections, currentTitle, buffer);
        if (sections.isEmpty()) {
            return List.of(new TextSection(currentTitle, normalized));
        }
        return sections;
    }

    private void appendTextSection(List<TextSection> sections, String title, StringBuilder buffer) {
        String text = cleanTextForChunking(buffer == null ? "" : buffer.toString());
        if (!text.isBlank()) {
            sections.add(new TextSection(title, text));
        }
    }

    private String normalizeSectionTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private boolean isHeadingLine(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isBlank() || trimmed.length() > 90) {
            return false;
        }
        if (Pattern.compile("^#{1,6}\\s+\\S.+$").matcher(trimmed).matches()) {
            return true;
        }
        if (Pattern.compile("^第[\\d一二三四五六七八九十百千万零〇两]+[章节篇课部分回讲][^。！？!?]{0,70}$").matcher(trimmed).matches()) {
            return true;
        }
        if (Pattern.compile("^[一二三四五六七八九十]+[、.．]\\s*\\S.{1,70}$").matcher(trimmed).matches()) {
            return true;
        }
        return Pattern.compile("^\\d+(?:\\.\\d+){0,4}[、.．]?\\s+\\S.{1,70}$").matcher(trimmed).matches()
            && !trimmed.matches(".*[。！？!?]$");
    }

    private String normalizeHeadingTitle(String line) {
        String trimmed = line == null ? "" : line.trim();
        Matcher markdownMatcher = Pattern.compile("^#{1,6}\\s+(.+?)\\s*#*$").matcher(trimmed);
        if (markdownMatcher.matches()) {
            return markdownMatcher.group(1).trim();
        }
        return trimmed;
    }

    /**
     * 对单个文本块进行语义分块。
     *
     * <p>根据文本内容选择分块策略：
     * <ul>
     *   <li>包含图片标记（[[material-image:...]]）：使用 {@link #chunkTextWithImageMarkers} 特殊处理</li>
     *   <li>纯文本：使用 {@link #semanticChunk} 语义分块</li>
     * </ul>
     *
     * @param content 文本内容
     * @return 分块后的文本片段列表
     */
    private List<String> chunkText(String content) {
        String normalized = cleanTextForChunking(content);
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.contains(IMAGE_MARKER_PREFIX)) {
            // 图片标记需要和相邻说明文字绑定，不能按普通段落切散。
            return chunkTextWithImageMarkers(normalized);
        }
        return semanticChunk(normalized);
    }

    /**
     * 核心语义分块算法。
     *
     * <p>分块策略：
     * <ol>
     *   <li>先按双换行拆分为段落</li>
     *   <li>逐个段落累积到缓冲区，当超过 CHUNK_MAX_SIZE(800) 时开始新片段</li>
     *   <li>超长段落（超过 800 字符）按句子拆分，超长句子再按 CHUNK_MAX_SIZE 强制截断</li>
     *   <li>截断时优先在逗号、分号、空白等"软边界"处断开</li>
     *   <li>最终为相邻片段添加 CHUNK_OVERLAP(120) 字符的重叠窗口，保持上下文连贯</li>
     * </ol>
     *
     * @param text 文本内容（已去除首尾空白）
     * @return 分块后的文本片段列表
     */
    private List<String> semanticChunk(String text) {
        List<String> paragraphs = splitParagraphs(text);
        List<String> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();

        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > CHUNK_MAX_SIZE) {
                // 超长段落不直接进入缓冲区，先按句子拆开，避免单个 chunk 超过检索和 Embedding 的理想长度。
                if (!buffer.isEmpty()) {
                    addChunk(chunks, buffer.toString().trim());
                    buffer.setLength(0);
                }
                chunks.addAll(splitBySentences(trimmed));
                continue;
            }
            if (buffer.length() + trimmed.length() + 1 > CHUNK_MAX_SIZE && !buffer.isEmpty()) {
                // 追加当前段会越过上限时先封存已有缓冲区，保持 chunk 长度稳定。
                addChunk(chunks, buffer.toString().trim());
                buffer.setLength(0);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n");
            }
            buffer.append(trimmed);
        }
        if (!buffer.isEmpty()) {
            addChunk(chunks, buffer.toString().trim());
        }

        if (chunks.size() <= 1) {
            return chunks.stream().filter(c -> !c.isBlank()).toList();
        }
        return addOverlap(chunks);
    }

    /**
     * 按双换行拆分文本为段落列表。
     * 同时清理段落内的多余空白和换行。
     *
     * @param text 原始文本
     * @return 段落列表（已去除空白段落）
     */
    private List<String> splitParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        String[] parts = text.split("\\n\\s*\\n+");
        for (String part : parts) {
            String paragraph = part.trim()
                .replaceAll("[ \\t]*\\n[ \\t]*", "\n")
                .replaceAll("[ \\t]{2,}", " ");
            if (!paragraph.isEmpty()) {
                paragraphs.add(paragraph);
            }
        }
        return paragraphs;
    }

    /**
     * 按句子拆分长文本（用于超长段落的进一步拆分）。
     *
     * <p>使用正则表达式匹配中英文句号、感叹号、问号、分号等标点作为句子边界。
     * 超长句子会进一步调用 {@link #splitLongSentence} 强制拆分。
     *
     * @param text 长文本
     * @return 分句后的文本片段列表
     */
    private List<String> splitBySentences(String text) {
        List<String> sentences = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("[^。！？!?.;；]+[。！？!?.;；]?")
            .matcher(text);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String sentence = matcher.group().trim();
            if (sentence.isEmpty()) {
                continue;
            }
            if (sentence.length() > CHUNK_MAX_SIZE) {
                if (!buffer.isEmpty()) {
                    addChunk(sentences, buffer.toString().trim());
                    buffer.setLength(0);
                }
                sentences.addAll(splitLongSentence(sentence));
                continue;
            }
            if (buffer.length() + sentence.length() + 1 > CHUNK_MAX_SIZE && !buffer.isEmpty()) {
                addChunk(sentences, buffer.toString().trim());
                buffer.setLength(0);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n");
            }
            buffer.append(sentence);
        }
        if (!buffer.isEmpty()) {
            addChunk(sentences, buffer.toString().trim());
        }
        return sentences;
    }

    /**
     * 对超长句子进行强制拆分。
     *
     * <p>按 CHUNK_MAX_SIZE(800) 字符为上限切分，截断时优先在"软边界"处断开
     * （逗号、分号、空白等），确保不会在词的中间截断。
     *
     * @param sentence 超长句子
     * @return 拆分后的文本片段列表
     */
    private List<String> splitLongSentence(String sentence) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < sentence.length()) {
            int end = Math.min(sentence.length(), start + CHUNK_MAX_SIZE);
            if (end < sentence.length()) {
                int boundary = findSoftBoundary(sentence, start, end);
                if (boundary > start + CHUNK_MIN_SIZE) {
                    end = boundary;
                }
            }
            addChunk(chunks, sentence.substring(start, end).trim());
            start = end;
        }
        return chunks;
    }

    /**
     * 在指定范围内查找"软边界"（逗号、分号、空白等），用于在合理的位置截断文本。
     * 从 end 位置向前搜索，确保截断后的内容不少于 CHUNK_MIN_SIZE(300) 字符。
     *
     * @param text  全文文本
     * @param start 起始位置（包含）
     * @param end   结束位置（不包含）
     * @return 建议的截断位置；找不到合适边界时返回 end
     */
    private int findSoftBoundary(String text, int start, int end) {
        int min = Math.min(end, start + CHUNK_MIN_SIZE);
        for (int index = end - 1; index > min; index--) {
            char ch = text.charAt(index);
            if (ch == '，' || ch == ',' || ch == '、' || ch == ';' || ch == '；' || Character.isWhitespace(ch)) {
                return index + 1;
            }
        }
        return end;
    }

    /**
     * 向片段列表添加文本（非空时才添加）。
     */
    private void addChunk(List<String> chunks, String text) {
        if (!text.isBlank()) {
            chunks.add(text);
        }
    }

    /**
     * 为相邻片段添加重叠窗口，保持上下文连贯性。
     *
     * <p>从第二个片段开始，每个片段的开头会添加前一个片段尾部的 CHUNK_OVERLAP(120) 字符。
     * 这样在检索时，即使匹配发生在两个片段的交界处，也能获取到完整的上下文。
     *
     * @param chunks 原始片段列表
     * @return 添加重叠后的片段列表
     */
    private List<String> addOverlap(List<String> chunks) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));
        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String overlap = tailOverlap(prev);
            result.add(overlap + " " + chunks.get(i));
        }
        return result;
    }

    /**
     * 提取文本尾部的重叠内容。
     *
     * <p>从文本尾部截取 CHUNK_OVERLAP(120) 字符，优先在句子边界处截断
     * （句号、感叹号、问号），避免从句子中间截取导致语义不完整。
     *
     * @param text 原始文本
     * @return 尾部重叠文本
     */
    private String tailOverlap(String text) {
        if (text.length() <= CHUNK_OVERLAP) {
            return text;
        }
        int start = text.length() - CHUNK_OVERLAP;
        for (int index = start; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?') {
                int boundary = index + 1;
                if (text.length() - boundary >= CHUNK_OVERLAP / 2) {
                    return text.substring(boundary).trim();
                }
            }
        }
        return text.substring(start).trim();
    }

    /**
     * 对包含图片标记的文本进行特殊分块处理。
     *
     * <p>图片标记（[[material-image:xxx]]）周围的文本与图片标记一起作为独立片段，
     * 确保图片和描述文字不会被分到不同的片段中。图片标记之间的普通文本仍按正常语义分块处理。
     *
     * @param content 包含图片标记的文本
     * @return 分块后的文本片段列表（图片标记为独立片段）
     */
    private List<String> chunkTextWithImageMarkers(String content) {
        List<String> chunks = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile(java.util.regex.Pattern.quote(IMAGE_MARKER_PREFIX) + "[^\\]]+" + java.util.regex.Pattern.quote(IMAGE_MARKER_SUFFIX))
            .matcher(content);
        int cursor = 0;
        while (matcher.find()) {
            chunks.addAll(chunkText(content.substring(cursor, matcher.start())));
            int nextMarker = content.indexOf(IMAGE_MARKER_PREFIX, matcher.end());
            int imageBlockEnd = nextMarker >= 0 ? nextMarker : content.length();
            // 当前图片到下一张图片之前的文字一起保留，方便 OCR、图注和正文在同一检索片段中命中。
            String imageBlock = content.substring(matcher.start(), imageBlockEnd).trim();
            if (!imageBlock.isBlank()) {
                chunks.add(imageBlock);
            }
            cursor = imageBlockEnd;
            matcher.region(cursor, content.length());
        }
        chunks.addAll(chunkText(content.substring(cursor)));
        return chunks.stream().filter(chunk -> !chunk.isBlank()).toList();
    }

    private Path assetDir(Path sourceFile) {
        return Path.of(sourceFile.toString() + ASSET_SUFFIX);
    }

    private Path previewPdfPath(Path sourceFile) {
        return Path.of(sourceFile.toString() + PREVIEW_SUFFIX).toAbsolutePath().normalize();
    }

    private Path previewPdfPath(LearningMaterialEntity material) {
        if (material.getStoragePath() == null || material.getStoragePath().isBlank()) {
            return null;
        }
        Path sourcePath = resolveStoredPath(material.getStoragePath());
        if (material.getSourceType() == MaterialSourceType.PDF) {
            return preferredPdfPreviewPath(sourcePath);
        }
        if (material.getSourceType() == MaterialSourceType.DOCX || material.getSourceType() == MaterialSourceType.WORD) {
            return previewPdfPath(sourcePath);
        }
        return null;
    }

    private Path preferredPdfPreviewPath(Path sourcePath) {
        Path previewPath = previewPdfPath(sourcePath);
        if (Files.exists(previewPath) && Files.isRegularFile(previewPath)) {
            return previewPath;
        }
        return sourcePath;
    }

    private String pageImageName(int pageNo) {
        return "page-" + pageNo + ".png";
    }

    private boolean isPageImageName(String fileName) {
        return java.util.regex.Pattern.compile(PAGE_IMAGE_RE).matcher(fileName).matches();
    }

    private boolean isPageRendered(Path sourceFile, String fileName) {
        return resolveAssetPath(sourceFile, fileName) != null;
    }

    private Map<Integer, List<Long>> chunksByPage(long materialId, int pageCount) {
        Map<Integer, List<Long>> result = new LinkedHashMap<>();
        List<MaterialChunkEntity> chunks = materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId);
        for (MaterialChunkEntity chunk : chunks) {
            if (chunk.getPageNo() == null || chunk.getId() == null) {
                continue;
            }
            result.computeIfAbsent(chunk.getPageNo(), ignored -> new ArrayList<>()).add(chunk.getId());
        }
        if (result.isEmpty() && pageCount > 0 && !chunks.isEmpty()) {
            return fallbackChunksByPage(chunks, pageCount);
        }
        return result;
    }

    /**
     * 为没有真实页码的资料估算页面与片段关系。
     *
     * <p>Word/PPT 转成预览 PDF 后能显示真实页面，但 POI 抽出的正文通常没有页码。
     * 如果只按比例硬切，最后一页很容易只映射到图片 OCR 片段，漏掉前一个正文片段。
     * 因此这里给每页补一个向前重叠片段，让边读边问能带上跨页正文上下文。</p>
     */
    private Map<Integer, List<Long>> fallbackChunksByPage(List<MaterialChunkEntity> chunks, int pageCount) {
        Map<Integer, List<Long>> result = new LinkedHashMap<>();
        int chunkCount = chunks.size();
        for (int pageNo = 1; pageNo <= pageCount; pageNo++) {
            int pageIndex = pageNo - 1;
            int start = (int) Math.floor((pageIndex * chunkCount) / (double) pageCount);
            int end = (int) Math.ceil(((pageIndex + 1) * chunkCount) / (double) pageCount);
            start = Math.max(0, start - 1);
            end = Math.min(chunkCount, Math.max(start + 1, end));
            for (int index = start; index < end; index++) {
                MaterialChunkEntity chunk = chunks.get(index);
                if (chunk.getId() != null) {
                    result.computeIfAbsent(pageNo, ignored -> new ArrayList<>()).add(chunk.getId());
                }
            }
        }
        return result;
    }

    private Path resolveAssetPath(Path sourceFile, String fileName) {
        Path dir = assetDir(sourceFile).toAbsolutePath().normalize();
        Path direct = dir.resolve(fileName).normalize();
        if (direct.startsWith(dir) && Files.exists(direct) && Files.isRegularFile(direct)) {
            return direct;
        }

        return null;
    }

    /**
     * 按需渲染 PDF 指定页面为 PNG 图片（从文件路径加载 PDF 版本）。
     *
     * <p>使用 Semaphore 限制并发渲染数量（默认 2），防止内存溢出。
     * 渲染结果保存到 .assets 目录下，如果已存在则直接返回。
     *
     * @param sourceFile 原始 PDF 文件路径（用于定位资源目录）
     * @param pdfFile    预览 PDF 文件路径（可能与 sourceFile 不同）
     * @param fileName   图片文件名（如 "page-3.png"）
     * @return 渲染后的图片文件路径；失败时返回 null
     */
    private Path renderPdfPageAsset(Path sourceFile, Path pdfFile, String fileName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile(PAGE_IMAGE_RE)
            .matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        int pageNo = Integer.parseInt(matcher.group(1));
        Path dir = assetDir(sourceFile).toAbsolutePath().normalize();
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir)) {
            return null;
        }
        if (Files.exists(target) && Files.isRegularFile(target)) {
            return target;
        }
        boolean acquired = false;
        try {
            // PDF 渲染占用内存高，用信号量限制并发，超时则让调用方返回空结果。
            acquired = renderSemaphore.tryAcquire(30, TimeUnit.SECONDS);
            if (!acquired) {
                return null;
            }
            if (Files.exists(target) && Files.isRegularFile(target)) {
                return target;
            }
            Files.createDirectories(dir);
            try (var document = Loader.loadPDF(pdfFile.toFile())) {
                if (pageNo < 1 || pageNo > document.getNumberOfPages()) {
                    return null;
                }
                return renderPdfPageAsset(sourceFile, document, pageNo - 1, fileName);
            }
        } catch (Exception exception) {
            log.warn("PDF page render failed for {} from {}", fileName, pdfFile, exception);
            return null;
        } finally {
            if (acquired) {
                renderSemaphore.release();
            }
        }
    }

    /**
     * 渲染 PDF 指定页面为 PNG 图片（使用已加载的 PDDocument 对象版本）。
     *
     * <p>使用 PDFRenderer 按指定 DPI（默认 144）渲染页面为 BufferedImage，
     * 然后以 PNG 格式写入磁盘。渲染结果缓存在 .assets 目录中。
     *
     * @param sourceFile 原始 PDF 文件路径（用于定位资源目录）
     * @param document   已加载的 PDDocument 对象
     * @param pageIndex  页面索引（从 0 开始）
     * @param fileName   输出图片文件名
     * @return 渲染后的图片文件路径；失败时返回 null
     */
    private Path renderPdfPageAsset(Path sourceFile, org.apache.pdfbox.pdmodel.PDDocument document, int pageIndex, String fileName) {
        Path dir = assetDir(sourceFile).toAbsolutePath().normalize();
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir)) {
            return null;
        }
        if (Files.exists(target) && Files.isRegularFile(target)) {
            return target;
        }
        try {
            if (pageIndex < 0 || pageIndex >= document.getNumberOfPages()) {
                return null;
            }
            Files.createDirectories(dir);
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage image = renderer.renderImageWithDPI(pageIndex, renderDpi);
            ImageIO.write(image, "png", target.toFile());
            return target;
        } catch (Exception exception) {
            log.warn("PDF page render failed for {} pageIndex={}", sourceFile, pageIndex, exception);
            return null;
        }
    }

    private String saveOfficeImageAsset(java.util.zip.ZipFile zipFile, String entryName, Path sourceFile, String scope, int index) throws IOException {
        String extension = extensionOf(entryName);
        if (extension.isBlank()) {
            extension = "bin";
        }
        String assetName = assetFileName(scope, index, extension);
        Path target = assetDir(sourceFile).resolve(assetName);
        Files.createDirectories(target.getParent());
        var entry = zipFile.getEntry(entryName);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("image entry not found");
        }
        try (var input = zipFile.getInputStream(entry)) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return assetName;
    }

    private String saveImageAsset(Path sourceFile, BufferedImage image, String scope, int index, String extension) throws IOException {
        String assetName = assetFileName(scope, index, extension);
        Path target = assetDir(sourceFile).resolve(assetName);
        Files.createDirectories(target.getParent());
        ImageIO.write(image, extension, target.toFile());
        return assetName;
    }

    private String imageBlock(String assetName, Path assetPath) {
        StringBuilder builder = new StringBuilder(imageMarker(assetName));
        String ocrText = ocrImageText(assetPath);
        if (!ocrText.isBlank()) {
            builder.append('\n')
                .append("[image ocr: ")
                .append(assetName)
                .append("]\n")
                .append(ocrText);
        }
        return builder.toString();
    }

    private String imageMarker(String assetName) {
        return IMAGE_MARKER_PREFIX + assetName + IMAGE_MARKER_SUFFIX;
    }

    private String assetFileName(String scope, int index, String extension) {
        String safeScope = sanitizeAssetFileName(scope);
        if (safeScope == null) {
            safeScope = "image";
        }
        String safeExtension = extension == null ? "bin" : extension.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
        if (safeExtension.isBlank()) {
            safeExtension = "bin";
        }
        return "%s-%03d.%s".formatted(safeScope, index, safeExtension);
    }

    private String sanitizeAssetFileName(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isBlank() || candidate.contains("/") || candidate.contains("\\") || candidate.equals(".") || candidate.equals("..")) {
            return null;
        }
        candidate = candidate.replaceAll("[^A-Za-z0-9._-]", "_");
        return candidate.isBlank() ? null : candidate;
    }

    private String extensionOf(String value) {
        String name = value == null ? "" : value;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String value) {
        String name = value == null ? "" : value;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        if (dot <= slash) {
            return name;
        }
        return name.substring(0, dot);
    }

    private String imageContentType(String fileName) {
        return switch (extensionOf(fileName)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "tif", "tiff" -> "image/tiff";
            default -> "application/octet-stream";
        };
    }

    private String normalizeZipEntryName(String value) {
        return value.replace('\\', '/');
    }

    /**
     * 使用 OCR（光学字符识别）从图片中提取文字。
     *
     * <p>调用外部 OCR 命令（默认为 Tesseract），支持自定义命令模板。
     * OCR 结果通过 stdout 获取，超时后强制终止进程。
     *
     * @param imagePath 图片文件路径
     * @return OCR 识别出的文字内容；OCR 未启用或识别失败时返回空字符串
     */
    private String ocrImageText(Path imagePath) {
        if (!ocrEnabled) {
            return "";
        }
        try {
            List<String> command = buildOcrCommand(imagePath);
            if (command.isEmpty()) {
                return "";
            }
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            CompletableFuture<String> outputFuture = readProcessOutputAsync(process);
            boolean finished = process.waitFor(ocrTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("OCR timed out after {} for image {} command={}", ocrTimeout, imagePath, command);
                return "";
            }
            if (process.exitValue() != 0) {
                log.warn("OCR command failed exitCode={} image={} output={}", process.exitValue(), imagePath, processOutput(outputFuture));
                return "";
            }
            return processOutput(outputFuture);
        } catch (Exception exception) {
            log.warn("OCR execution failed for image {}", imagePath, exception);
            return "";
        }
    }

    private List<String> buildOcrCommand(Path imagePath) {
        if (ocrCommandTemplate != null && !ocrCommandTemplate.isBlank()) {
            return tokenizeCommandTemplate(ocrCommandTemplate)
                .stream()
                .map(part -> part
                    .replace("{image}", imagePath.toString())
                    .replace("{lang}", ocrLang))
                .filter(part -> !part.isBlank())
                .toList();
        }
        return List.of(ocrCommand, imagePath.toString(), "stdout", "-l", ocrLang);
    }

    private List<String> tokenizeCommandTemplate(String template) {
        List<String> parts = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\"([^\"]*)\"|'([^']*)'|\\S+")
            .matcher(template);
        while (matcher.find()) {
            String doubleQuoted = matcher.group(1);
            String singleQuoted = matcher.group(2);
            parts.add(doubleQuoted != null ? doubleQuoted : singleQuoted != null ? singleQuoted : matcher.group());
        }
        return parts;
    }

    /**
     * 为文本生成 Embedding 向量的 JSON 字符串。
     *
     * <p>处理流程：
     * <ol>
     *   <li>清理文本（移除图片标记、多余空白）</li>
     *   <li>计算文本的 SHA-256 哈希作为缓存 key</li>
     *   <li>查询内存缓存，命中则直接返回</li>
     *   <li>调用 EmbeddingClient 生成向量（OpenAI 兼容接口）</li>
     *   <li>将向量序列化为 JSON 字符串并缓存</li>
     *   <li>缓存超限（2048 条）时清空重新积累</li>
     * </ol>
     *
     * @param text 文本内容
     * @return Embedding 向量的 JSON 字符串；生成失败时返回 null
     */
    private String toEmbeddingJson(String text) {
        String cleanedText = cleanEmbeddingText(text);
        if (cleanedText.isBlank()) {
            return null;
        }
        String cacheKey = sha256(cleanedText.getBytes(StandardCharsets.UTF_8));
        String cached = embeddingJsonCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            // 以清洗后的文本作为缓存粒度，同一片段重复解析时避免再次请求外部 Embedding 服务。
            String embeddingJson = embeddingClient.embedDocument(cleanedText)
                .filter(embedding -> !embedding.isEmpty())
                .map(embedding -> {
                    try {
                        return objectMapper.writeValueAsString(embedding);
                    } catch (Exception exception) {
                        return null;
                    }
                })
                .orElse(null);
            if (embeddingJson != null) {
                if (embeddingJsonCache.size() >= EMBEDDING_CACHE_MAX_ENTRIES) {
                    // 简单上限保护：缓存只做性能优化，超限清空不会影响正确性。
                    embeddingJsonCache.clear();
                }
                embeddingJsonCache.putIfAbsent(cacheKey, embeddingJson);
            }
            return embeddingJson;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 将 Embedding 向量的 JSON 字符串解析为 Double 列表。
     *
     * @param embeddingJson 向量的 JSON 字符串
     * @return Double 列表；解析失败时返回 null
     */
    private List<Double> parseEmbeddingJson(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(embeddingJson);
            if (!root.isArray() || root.isEmpty()) {
                return null;
            }
            List<Double> embedding = new ArrayList<>(root.size());
            for (JsonNode value : root) {
                if (!value.isNumber()) {
                    return null;
                }
                embedding.add(value.asDouble());
            }
            return embedding.isEmpty() ? null : embedding;
        } catch (Exception exception) {
            return null;
        }
    }

    /**
     * 清理文本用于 Embedding 生成。
     * 移除图片标记、OCR 标记和多余空白，保留纯文本内容。
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    private String cleanEmbeddingText(String text) {
        if (text == null) {
            return "";
        }
        return text
            .replaceAll("\\[\\[material-image:[^\\]]+\\]\\]\\s*", " ")
            .replaceAll("\\[image ocr:[^\\]]*\\]\\s*", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    /**
     * 构建知识片段的摘要。
     *
     * <p>提取文本的第一个完整句子（最长 220 字符内搜索句末标点），
     * 如果找不到完整句子则截取前 180 字符作为摘要。
     *
     * @param text 片段文本
     * @return 摘要文本；文本为空时返回 null
     */
    private String buildChunkSummary(String text) {
        String cleaned = cleanEmbeddingText(text);
        if (cleaned.isBlank()) {
            return null;
        }
        int sentenceEnd = firstSentenceEnd(cleaned);
        int end = sentenceEnd > 0 ? sentenceEnd : Math.min(cleaned.length(), 180);
        String summary = cleaned.substring(0, end).trim();
        if (summary.length() > 180) {
            summary = summary.substring(0, 180).trim();
        }
        return summary.isBlank() ? null : summary;
    }

    private int firstSentenceEnd(String text) {
        int limit = Math.min(text.length(), 220);
        for (int index = 0; index < limit; index++) {
            char c = text.charAt(index);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                return index + 1;
            }
        }
        return -1;
    }

    /**
     * 从文本中提取关键词。
     *
     * <p>提取策略：
     * <ol>
     *   <li>使用正则匹配中文、英文、数字、特殊符号组成的 2 字符以上词组</li>
     *   <li>过滤停用词（如 "的"、"是"、"the"、"and" 等常见词）</li>
     *   <li>按词频降序排列，同等词频按词长降序</li>
     *   <li>取前 8 个关键词，逗号分隔输出</li>
     *   <li>总长度不超过 500 字符</li>
     * </ol>
     *
     * @param text 片段文本
     * @return 关键词字符串（逗号分隔）；文本为空时返回 null
     */
    private String buildChunkKeywords(String text) {
        String cleaned = cleanEmbeddingText(text);
        if (cleaned.isBlank()) {
            return null;
        }
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        Matcher matcher = KEYWORD_PATTERN.matcher(cleaned);
        while (matcher.find()) {
            String keyword = normalizeKeyword(matcher.group());
            if (keyword == null) {
                continue;
            }
            frequencies.merge(keyword, 1, Integer::sum);
        }
        String keywords = frequencies.entrySet().stream()
            .sorted((left, right) -> {
                int byFrequency = Integer.compare(right.getValue(), left.getValue());
                if (byFrequency != 0) {
                    return byFrequency;
                }
                return Integer.compare(right.getKey().length(), left.getKey().length());
            })
            .map(Map.Entry::getKey)
            .limit(8)
            .collect(java.util.stream.Collectors.joining(", "));
        if (keywords.isBlank()) {
            return null;
        }
        return keywords.length() <= CHUNK_KEYWORDS_MAX_LENGTH
            ? keywords
            : keywords.substring(0, CHUNK_KEYWORDS_MAX_LENGTH);
    }

    /**
     * 规范化关键词：去除首尾标点和空白，过滤停用词和过短的词。
     * 英文关键词转为小写，中文保持原样。
     *
     * @param raw 原始词组
     * @return 规范化后的关键词；不符合条件时返回 null
     */
    private String normalizeKeyword(String raw) {
        if (raw == null) {
            return null;
        }
        String keyword = raw.trim()
            .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
        if (keyword.length() < 2) {
            return null;
        }
        String lower = keyword.toLowerCase(Locale.ROOT);
        if (KEYWORD_STOP_WORDS.contains(lower)) {
            return null;
        }
        return lower.matches("[a-z0-9+#./-]+") ? lower : keyword;
    }

    private String normalizeTitle(String title, String originalName) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        return originalName;
    }

    private String normalizeText(String value, String fallback) {
        String text = value == null ? "" : value.trim();
        return text.isBlank() ? fallback : text;
    }

    private String normalizeOptionalText(String value) {
        return value == null ? null : (value.trim().isBlank() ? null : value.trim());
    }

    private String normalizeClientUploadId(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isBlank()) {
            throw new BusinessException(400, "clientUploadId cannot be blank");
        }
        if (text.length() <= CLIENT_UPLOAD_ID_MAX_LENGTH) {
            return text;
        }
        // 旧前端曾把完整文件名拼进 clientUploadId；超长时改为稳定摘要，避免数据库截断导致 500。
        return "legacy-" + sha256(text.getBytes(StandardCharsets.UTF_8)).substring(0, 57);
    }

    /**
     * 计算字节数组的 SHA-256 哈希值。
     *
     * @param bytes 输入字节数组
     * @return SHA-256 哈希的十六进制字符串（小写）
     */
    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }

    /**
     * 计算文件的 SHA-256 哈希值（流式读取，避免大文件占用过多内存）。
     *
     * @param path 文件路径
     * @return SHA-256 哈希的十六进制字符串（小写）
     * @throws IOException 文件读取失败时抛出
     */
    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }

    /**
     * 流式比较两个文件的 SHA-256，避免为了判断重复分片把内容整体读入内存。
     */
    private boolean sameFileHash(Path first, Path second) throws IOException {
        return sha256(first).equalsIgnoreCase(sha256(second));
    }

    /**
     * 将校验通过的临时分片移动为正式分片。
     * 优先使用原子移动，避免正式分片被半写入；少数文件系统不支持时退回同目录普通移动。
     */
    private void moveUploadedPart(Path tempPartPath, Path partPath) throws IOException {
        try {
            Files.move(tempPartPath, partPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempPartPath, partPath);
        }
    }

    /**
     * 删除存储的原始文件。
     */
    private void deleteStoredFile(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolveStoredPath(storagePath));
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void deletePreviewFile(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(previewPdfPath(resolveStoredPath(storagePath)));
        } catch (IOException ignored) {
            // ignore
        }
    }

    private void deletePdfPreviewCopy(Path sourcePath) {
        try {
            Files.deleteIfExists(previewPdfPath(sourcePath));
        } catch (IOException ignored) {
            // ignore
        }
    }

    /**
     * 删除资料的资源文件目录（.assets，包含提取的图片等）。
     * 递归删除目录中的所有文件和子目录。
     */
    private void deleteStoredAssets(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        Path dir = assetDir(resolveStoredPath(storagePath));
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // ignore
                }
            });
        } catch (IOException ignored) {
            // ignore
        }
    }

    /**
     * 图片计数器（用于在 ZIP 解析时为图片生成递增编号）。
     */
    private static final class ImageCounter {
        private int value;

        private int next() {
            value += 1;
            return value;
        }
    }

    /**
     * 解析并校验网页 URL。
     *
     * <p>校验规则：
     * <ul>
     *   <li>URL 不能为空</li>
     *   <li>如果没有协议前缀，自动添加 "http://"</li>
     *   <li>仅支持 http/https 协议</li>
     *   <li>必须包含有效的主机名</li>
     *   <li>通过 OutboundUrlGuard 校验是否为公网地址（防止 SSRF 攻击）</li>
     * </ul>
     *
     * @param sourceUrl 原始 URL 字符串
     * @return 校验通过的 URI 对象
     * @throws BusinessException URL 无效或不安全时抛出 400
     */
    private URI parseWebUri(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new BusinessException(400, "source url cannot be empty");
        }
        String normalizedUrl = sourceUrl.trim();
        if (!normalizedUrl.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) {
            normalizedUrl = "http://" + normalizedUrl;
        }
        URI uri;
        try {
            uri = URI.create(normalizedUrl);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "Invalid source URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BusinessException(400, "only http/https source urls are supported");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new BusinessException(400, "Source URL host is missing");
        }
        return outboundUrlGuard.requirePublicHttpUrl(uri, false);
    }

    /**
     * 发送 HTTP GET 请求获取网页内容。
     *
     * <p>特性：
     * <ul>
     *   <li>手动处理 HTTP 重定向（最多 5 次），每次重定向都经过 OutboundUrlGuard 校验</li>
     *   <li>根据 Content-Type 响应头自动检测字符编码</li>
     *   <li>校验响应体大小不超过 maxWebBytes 限制</li>
     *   <li>设置了 User-Agent 请求头标识来源</li>
     * </ul>
     *
     * @param uri 请求的 URI
     * @return 获取到的网页资源（包含响应体、响应头和字符编码）
     * @throws BusinessException 请求失败或内容过大时抛出 400
     */
    private FetchedWebResource fetchWebResource(URI uri) {
        URI currentUri = outboundUrlGuard.requirePublicHttpUrl(uri, false);
        try {
            HttpResponse<byte[]> response = null;
            for (int redirects = 0; redirects <= 5; redirects++) {
                HttpRequest request = HttpRequest.newBuilder(currentUri)
                    .timeout(WEB_REQUEST_TIMEOUT)
                    .header("User-Agent", "LearningAssistantBot/1.0")
                    .GET()
                    .build();
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (!isRedirect(response.statusCode())) {
                    break;
                }
                currentUri = redirectUri(currentUri, response);
            }
            if (response == null) {
                throw new BusinessException(400, "web fetch failed");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(400, "Web fetch failed, status: " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > maxWebBytes) {
                throw new BusinessException(400, "linked material is too large");
            }
            Charset charset = resolveCharset(response.headers().firstValue("Content-Type").orElse(""));
            return new FetchedWebResource(body, response.headers(), charset);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(400, "web fetch failed");
        }
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private URI redirectUri(URI currentUri, HttpResponse<byte[]> response) {
        String location = response.headers().firstValue("Location")
            .orElseThrow(() -> new BusinessException(400, "Web redirect missing location"));
        URI nextUri;
        try {
            nextUri = currentUri.resolve(location);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, "Invalid redirect URL");
        }
        return outboundUrlGuard.requirePublicHttpUrl(nextUri, false);
    }

    private Charset resolveCharset(String contentType) {
        String lower = contentType.toLowerCase(Locale.ROOT);
        int charsetIndex = lower.indexOf("charset=");
        if (charsetIndex < 0) {
            return StandardCharsets.UTF_8;
        }
        String charsetName = contentType.substring(charsetIndex + "charset=".length()).split(";")[0].trim();
        try {
            return Charset.forName(charsetName);
        } catch (Exception exception) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * 清理 HTML 内容，提取纯文本。
     *
     * <p>清理步骤：
     * <ol>
     *   <li>移除 script、style、noscript 标签及其内容</li>
     *   <li>移除 HTML 注释</li>
     *   <li>移除所有 HTML 标签</li>
     *   <li>反转义 HTML 实体（&amp;nbsp;、&amp;lt;、&amp;gt; 等）</li>
     *   <li>合并连续空白为单个空格</li>
     * </ol>
     *
     * @param html HTML 原始内容
     * @return 清理后的纯文本
     */
    private String cleanWebText(String html) {
        String text = html == null ? "" : html;
        text = Pattern.compile("(?is)<(script|style|noscript)[^>]*>.*?</\\1>").matcher(text).replaceAll(" ");
        text = Pattern.compile("(?is)<!--.*?-->").matcher(text).replaceAll(" ");
        text = Pattern.compile("(?is)<[^>]+>").matcher(text).replaceAll(" ");
        text = unescapeHtml(text);
        return text.replaceAll("\\s+", " ").trim();
    }

    private String unescapeHtml(String value) {
        return value
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'");
    }

    private String inferWebOriginalName(URI uri, FetchedWebResource resource) {
        String contentDispositionName = fileNameFromContentDisposition(resource.headers());
        if (!contentDispositionName.isBlank()) {
            return sanitizeFileName(contentDispositionName);
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        String candidate = path.substring(path.lastIndexOf('/') + 1);
        if (candidate.isBlank()) {
            candidate = uri.getHost();
        }
        candidate = URLDecoder.decode(candidate, StandardCharsets.UTF_8);
        candidate = sanitizeFileName(candidate);
        if (candidate.isBlank()) {
            return "web-page";
        }
        return candidate.length() <= 120 ? candidate : candidate.substring(0, 120);
    }

    private String fileNameFromContentDisposition(HttpHeaders headers) {
        return headers.firstValue("Content-Disposition")
            .map(value -> {
                java.util.regex.Matcher utf8Matcher = java.util.regex.Pattern
                    .compile("(?i)filename\\*=UTF-8''([^;]+)")
                    .matcher(value);
                if (utf8Matcher.find()) {
                    return URLDecoder.decode(utf8Matcher.group(1).trim(), StandardCharsets.UTF_8);
                }
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?i)filename=\"?([^\";]+)\"?")
                    .matcher(value);
                return matcher.find() ? matcher.group(1).trim() : "";
            })
            .orElse("");
    }

    private MaterialSourceType inferWebSourceType(FetchedWebResource resource, String originalName) {
        MaterialSourceType byName = parseSourceType(null, originalName);
        if (byName != MaterialSourceType.TXT || hasKnownExtension(originalName)) {
            return byName;
        }
        String contentType = resource.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.contains("pdf")) {
            return MaterialSourceType.PDF;
        }
        if (contentType.contains("wordprocessingml") || contentType.contains("officedocument.wordprocessingml")) {
            return MaterialSourceType.DOCX;
        }
        if (contentType.contains("presentationml") || contentType.contains("officedocument.presentationml")) {
            return MaterialSourceType.PPTX;
        }
        if (contentType.contains("spreadsheetml") || contentType.contains("officedocument.spreadsheetml")) {
            return MaterialSourceType.XLSX;
        }
        if (contentType.contains("markdown")) {
            return MaterialSourceType.MD;
        }
        if (contentType.contains("html")) {
            return MaterialSourceType.HTML;
        }
        if (contentType.startsWith("text/")) {
            return MaterialSourceType.TXT;
        }
        return byName;
    }

    private boolean hasKnownExtension(String originalName) {
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        return lowerName.endsWith(".pdf")
            || lowerName.endsWith(".docx")
            || lowerName.endsWith(".pptx")
            || lowerName.endsWith(".xlsx")
            || lowerName.endsWith(".md")
            || lowerName.endsWith(".txt")
            || lowerName.endsWith(".html")
            || lowerName.endsWith(".htm");
    }

    private String ensureWebExtension(String originalName, MaterialSourceType sourceType) {
        if (hasKnownExtension(originalName)) {
            return originalName;
        }
        String extension = switch (sourceType) {
            case PDF -> ".pdf";
            case DOCX, WORD -> ".docx";
            case PPTX, PPT -> ".pptx";
            case XLSX -> ".xlsx";
            case MD -> ".md";
            case HTML, WEB -> ".html";
            case TXT -> ".txt";
        };
        return originalName + extension;
    }

    private String sanitizeFileName(String value) {
        String candidate = value == null ? "" : value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return candidate.length() <= 120 ? candidate : candidate.substring(0, 120);
    }

    private String contentTypeFor(MaterialSourceType sourceType, String originalName, Path path) {
        return switch (sourceType) {
            case PDF -> "application/pdf";
            case DOCX, WORD -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case PPTX, PPT -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case MD, TXT -> "text/plain; charset=" + textFileCharset(path);
            case HTML, WEB -> "text/html; charset=utf-8";
        };
    }

    private String textFileCharset(Path path) {
        try {
            byte[] bytes;
            try (var input = Files.newInputStream(path)) {
                bytes = input.readNBytes(64 * 1024);
            }
            if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
                return "utf-8";
            }
            if (startsWith(bytes, 0xFF, 0xFE)) {
                return "utf-16le";
            }
            if (startsWith(bytes, 0xFE, 0xFF)) {
                return "utf-16be";
            }
            decodeStrict(bytes, StandardCharsets.UTF_8);
            return "utf-8";
        } catch (CharacterCodingException exception) {
            return "GB18030";
        } catch (IOException exception) {
            return "utf-8";
        }
    }

    private void appendWordParagraphsLegacy(StringBuilder builder, String xml) {
        appendWordParagraphs(builder, xml);
    }

    /**
     * 解析进度回调函数接口。
     * 用于在长时间解析过程中实时更新进度信息到数据库。
     */
    @FunctionalInterface
    private interface ParseProgressListener {
        /**
         * 报告解析进度。
         *
         * @param percent 当前进度百分比（0-100）
         * @param stage   当前阶段名称（如 "提取文本"、"OCR 识别"）
         * @param message 详细描述（如 "正在解析 PDF 第 3/10 页"）
         */
        void onProgress(int percent, String stage, String message);
    }

    /**
     * 解析后的资料内容（包含多个文本块）。
     * 每个文本块关联一个页码和章节标题。
     */
    private record ParsedMaterial(List<ParsedBlock> blocks, List<PageTextLayerDraft> textLayers, Integer pageCount) {
        private ParsedMaterial(List<ParsedBlock> blocks, List<PageTextLayerDraft> textLayers) {
            this(blocks, textLayers, null);
        }

        private ParsedMaterial(List<ParsedBlock> blocks) {
            this(blocks, List.of(), null);
        }

        /** 创建单文本块的解析结果（用于 TXT/MD/HTML 等单文档格式） */
        private static ParsedMaterial single(String text) {
            String normalized = text == null ? "" : text.trim();
            return new ParsedMaterial(normalized.isBlank() ? List.of() : List.of(new ParsedBlock(normalized, null, null)));
        }

        /** 判断所有文本块是否都为空 */
        private boolean isBlank() {
            return blocks.stream().allMatch(block -> block.text() == null || block.text().isBlank())
                && (textLayers == null || textLayers.stream().allMatch(block -> block.text() == null || block.text().isBlank()));
        }
    }

    /** 扫描 PDF 单页解析结果，包含用于检索的文本和可叠加到阅读器页面的透明文本层。 */
    private record ScannedPdfPageResult(String text, List<PageTextLayerDraft> textLayers) {
    }

    /**
     * 解析后的文本块。
     *
     * @param text         文本内容
     * @param pageNo       页码（可选，PDF 有页码概念时使用）
     * @param sectionTitle 章节标题（可选，如 "Page 3"、"Slide 5"）
     */
    private record ParsedBlock(String text, Integer pageNo, String sectionTitle) {
    }

    /**
     * 页面文本层草稿，保存原文页面上可选中文本的坐标信息。
     */
    private record PageTextLayerDraft(
        Integer pageNo,
        Integer blockIndex,
        String text,
        String blockType,
        String source,
        Double pageWidth,
        Double pageHeight,
        Double bboxX,
        Double bboxY,
        Double bboxWidth,
        Double bboxHeight,
        Double confidence
    ) {
    }

    /**
     * 知识片段草稿（尚未保存到数据库）。
     *
     * @param text         片段文本
     * @param pageNo       页码
     * @param sectionTitle 章节标题
     */
    private record ChunkDraft(String text, Integer pageNo, String sectionTitle) {
    }

    /** 标题感知切片前的章节文本。 */
    private record TextSection(String title, String text) {
    }

    /**
     * 从网页获取到的资源。
     *
     * @param body    响应体字节数组
     * @param headers HTTP 响应头
     * @param charset 响应体的字符编码
     */
    private record FetchedWebResource(byte[] body, HttpHeaders headers, Charset charset) {
        /** 将响应体按指定编码转换为字符串 */
        private String textBody() {
            return new String(body, charset);
        }
    }
}
