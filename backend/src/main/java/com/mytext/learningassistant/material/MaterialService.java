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

    /** 智能问答临时资料默认最大大小：500MB；更大的文件建议先作为持久资料上传。 */
    private static final long DEFAULT_MAX_TEMPORARY_MATERIAL_BYTES = 500L * 1024L * 1024L;

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

    /** 内联 PDF OCR 的最大文件大小限制：100MB */
    private static final long DEFAULT_INLINE_PDF_OCR_MAX_BYTES = 100L * 1024L * 1024L;

    /** 内联 PDF OCR 的最大页数限制 */
    private static final int DEFAULT_INLINE_PDF_OCR_MAX_PAGES = 200;

    /** PDF 压缩的最小文件大小阈值：64MB */
    private static final long DEFAULT_PDF_COMPRESSION_MIN_BYTES = 64L * 1024L * 1024L;

    /** PDF 压缩的目标 DPI */
    private static final int DEFAULT_PDF_COMPRESSION_TARGET_DPI = 144;

    /** MinerU 输出的默认归一化页面尺寸，content_list 的 bbox 通常使用 0~1000 坐标系。 */
    private static final double MINERU_NORMALIZED_PAGE_SIZE = 1000.0;

    /** 单个文本层块最大保留字符数，避免异常解析结果把整份文档塞进一个 overlay 节点。 */
    private static final int PAGE_TEXT_BLOCK_MAX_LENGTH = 4000;

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

    /** 超过该切片数时先完成上传，再由后台补齐向量索引，避免大资料进度条长时间假死。 */
    private static final int DEFER_VECTOR_INDEX_CHUNK_THRESHOLD = 300;

    /** 关键词停用词列表，这些常见词不会作为关键词输出 */
    private static final List<String> KEYWORD_STOP_WORDS = List.of(
        "the", "and", "for", "with", "that", "this", "from", "into", "are", "was", "were", "has", "have",
        "what", "how", "why", "where", "which", "does", "do", "did", "can", "could", "would", "should",
        "什么", "怎么", "为什么", "哪里", "哪个", "哪些", "一个", "这个", "那个", "以及", "如果", "因为"
    );

    // ======================== 依赖注入的仓库和工具 ========================
    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final MaterialPageTextBlockRepository materialPageTextBlockRepository;
    private final MaterialSummaryRepository materialSummaryRepository;
    private final RagQuestionSourceRepository ragQuestionSourceRepository;
    private final MaterialUploadSessionRepository materialUploadSessionRepository;
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
    /** 文档解析器提供方：legacy 表示使用内置解析器，mineru 表示优先调用 MinerU。 */
    private final String documentParserProvider;
    /** MinerU CLI 命令，支持直接写 mineru，也支持写完整可执行文件路径。 */
    private final String mineruCommand;
    /** MinerU 输出工作区，按资料生成独立子目录，避免不同上传互相覆盖。 */
    private final Path mineruWorkspace;
    /** MinerU 单次解析超时时间。 */
    private final Duration mineruTimeout;
    /** MinerU 模型下载来源；Windows 下可设置为 modelscope，避免 HuggingFace 缓存符号链接权限问题。 */
    private final String mineruModelSource;
    /** MinerU 失败时是否自动回退到 legacy 解析器。 */
    private final boolean mineruFallbackEnabled;
    /** PDF 已包含原生文本时是否跳过 MinerU，避免大文本 PDF 先等待 MinerU 超时再回退。 */
    private final boolean mineruSkipTextPdf;
    /** 判断 PDF 是否已有原生文本时最多抽样的页数。 */
    private final int mineruTextPdfDetectPages;
    private final long maxMaterialBytes;
    private final long maxTemporaryMaterialBytes;
    private final long maxWebBytes;

    // ======================== 线程和缓存 ========================
    /** 上传会话后台处理线程池（2 个线程） */
    private final ExecutorService uploadExecutor = Executors.newFixedThreadPool(2);
    /** 外部命令输出读取线程池，避免 MinerU 等命令输出过多时阻塞进程结束。 */
    private final ExecutorService processOutputExecutor = Executors.newCachedThreadPool();
    /** PDF 页面渲染并发信号量（限制同时渲染 2 个页面，防止内存溢出） */
    private final Semaphore renderSemaphore = new Semaphore(2);
    /** Embedding 向量缓存（key 为文本 SHA-256，value 为 JSON 格式的向量） */
    private final ConcurrentMap<String, String> embeddingJsonCache = new ConcurrentHashMap<>();

    /**
     * 构造函数 -- 通过 Spring 依赖注入初始化所有组件和配置。
     * 大量参数通过 {@code @Value} 从 application.properties / application.yml 中读取。
     */
    public MaterialService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        MaterialPageTextBlockRepository materialPageTextBlockRepository,
        MaterialSummaryRepository materialSummaryRepository,
        RagQuestionSourceRepository ragQuestionSourceRepository,
        MaterialUploadSessionRepository materialUploadSessionRepository,
        UsageRecordRepository usageRecordRepository,
        ObjectMapper objectMapper,
        EmbeddingClient embeddingClient,
        VectorStoreClient vectorStoreClient,
        OutboundUrlGuard outboundUrlGuard,
        @Value("${app.storage-dir:${user.dir}/target/learning-assistant-files}") String storageDir,
        @Value("${app.ocr.enabled:false}") boolean ocrEnabled,
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
        @Value("${app.document-parser.provider:legacy}") String documentParserProvider,
        @Value("${app.mineru.command:mineru}") String mineruCommand,
        @Value("${app.mineru.workspace:${user.dir}/target/mineru-output}") String mineruWorkspace,
        @Value("${app.mineru.timeout:15m}") Duration mineruTimeout,
        @Value("${app.mineru.model-source:}") String mineruModelSource,
        @Value("${app.mineru.fallback-enabled:true}") boolean mineruFallbackEnabled,
        @Value("${app.mineru.skip-text-pdf:true}") boolean mineruSkipTextPdf,
        @Value("${app.mineru.text-pdf-detect-pages:5}") int mineruTextPdfDetectPages,
        @Value("${app.material.max-file-bytes:2147483648}") long maxMaterialBytes,
        @Value("${app.material.max-temporary-file-bytes:524288000}") long maxTemporaryMaterialBytes,
        @Value("${app.material.max-web-bytes:10485760}") long maxWebBytes
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.materialPageTextBlockRepository = materialPageTextBlockRepository;
        this.materialSummaryRepository = materialSummaryRepository;
        this.ragQuestionSourceRepository = ragQuestionSourceRepository;
        this.materialUploadSessionRepository = materialUploadSessionRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.objectMapper = objectMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStoreClient = vectorStoreClient;
        this.outboundUrlGuard = outboundUrlGuard;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
        this.ocrEnabled = ocrEnabled;
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
        this.documentParserProvider = documentParserProvider == null || documentParserProvider.isBlank()
            ? "legacy"
            : documentParserProvider.trim().toLowerCase(Locale.ROOT);
        this.mineruCommand = mineruCommand == null || mineruCommand.isBlank() ? "mineru" : mineruCommand.trim();
        this.mineruWorkspace = Path.of(mineruWorkspace == null || mineruWorkspace.isBlank()
                ? Path.of(System.getProperty("user.dir"), "target", "mineru-output").toString()
                : mineruWorkspace)
            .toAbsolutePath()
            .normalize();
        this.mineruTimeout = mineruTimeout == null ? Duration.ofMinutes(15) : mineruTimeout;
        this.mineruModelSource = normalizeOptionalText(mineruModelSource);
        this.mineruFallbackEnabled = mineruFallbackEnabled;
        this.mineruSkipTextPdf = mineruSkipTextPdf;
        this.mineruTextPdfDetectPages = mineruTextPdfDetectPages <= 0 ? 5 : mineruTextPdfDetectPages;
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
        processOutputExecutor.shutdownNow();
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
        ParsedMaterial parsed = parseMaterial(sourceType, storagePath);
        return saveMaterial(ownerId, title, sourceType, originalName, storagePath, sourceUrl, file.getSize(), parsed);
    }

    /**
     * 解析临时资料文件，只返回提取文本，不创建资料记录。
     */
    public TemporaryMaterialResponse parseTemporary(long ownerId, String title, String sourceTypeValue, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "file cannot be empty");
        }
        if (file.getSize() > maxTemporaryMaterialBytes) {
            throw new BusinessException(400, "智能问答临时资料最大支持 500MB；大文件请切换到资料问答上传，系统会在后台解析并显示进度。");
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
            return new TemporaryMaterialResponse(
                UUID.randomUUID().toString(),
                normalizedTitle,
                originalName,
                sourceType.name(),
                text,
                excerpt(text)
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
        ParsedMaterial parsed = htmlPage ? ParsedMaterial.single(content) : parseMaterial(sourceType, storagePath);
        return saveMaterial(ownerId, title, htmlPage ? MaterialSourceType.WEB : sourceType, originalName, storagePath, uri.toString(), fileSize, parsed);
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
            if (!sameUploadSessionMetadata(existing, title, originalName, sourceType, sourceUrl, fileSize, chunkSize, totalChunks)) {
                if (isDiscardableUploadSession(existing)) {
                    discardFailedUploadSession(existing);
                    recreateSession = true;
                } else {
                    throw new BusinessException(409, "Upload metadata mismatch");
                }
            }
            if (!recreateSession && existing.getStatus() == MaterialUploadSessionStatus.FAILED) {
                discardFailedUploadSession(existing);
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
        applyPreviewMetadata(material, sourcePath, material.getSourceType());
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
        materialSummaryRepository.deleteByMaterialIdAndUserId(materialId, ownerId);
        ragQuestionSourceRepository.deleteByMaterialId(materialId);
        vectorStoreClient.deleteMaterial(ownerId, materialId);
        materialPageTextBlockRepository.deleteByMaterialId(materialId);
        materialChunkRepository.deleteByMaterialId(materialId);
        learningMaterialRepository.delete(material);
        deleteStoredAssets(material.getStoragePath());
        deleteStoredFile(material.getStoragePath());
        deletePreviewFile(material.getStoragePath());
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
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 后台线程依赖刚写入的 session/material 状态，必须等当前事务提交后再读取。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    uploadExecutor.submit(() -> processUploadSession(sessionId));
                }
            });
            return;
        }
        uploadExecutor.submit(() -> processUploadSession(sessionId));
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
        if (session == null) {
            return;
        }
        if (session.getStatus() == MaterialUploadSessionStatus.SUCCESS) {
            return;
        }
        if (session.getStatus() != MaterialUploadSessionStatus.PROCESSING) {
            return;
        }
        try {
            Path finalPath = resolveStoredPath(session.getStoragePath());
            Files.createDirectories(finalPath.getParent());
            updateMaterialParseProgress(session, 10, "合并文件", "正在合并上传分片");
            // 合并和整文件校验先完成，再进入解析，避免半文件被 PDFBox/ZIP 解析器消费。
            assembleUploadedFile(session, finalPath);
            updateMaterialParseProgress(session, 18, "校验文件", "正在校验文件完整性");
            validateUploadedFileChecksum(session, finalPath);
            MaterialSourceType sourceType = session.getSourceType();
            updateMaterialParseProgress(session, 30, "提取文本", "正在从文件中提取可检索文本");
            ParsedMaterial parsed = parseMaterial(sourceType, finalPath, (percent, stage, message) ->
                updateMaterialParseProgress(session, percent, stage, message));
            if (parsed.isBlank()) {
                throw new BusinessException(400, "material parsing failed");
            }
            updateMaterialParseProgress(session, 52, "切分文本", "正在按段落和页码生成知识片段");
            LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(session.getMaterialId(), session.getOwnerId())
                .orElseThrow(() -> new BusinessException(404, "Material not found"));
            material.setTitle(session.getTitle());
            material.setSourceType(sourceType);
            material.setOriginalName(session.getOriginalName());
            material.setStoragePath(storagePathValue(finalPath));
            material.setSourceUrl(session.getSourceUrl());
            material.setFileSize(session.getFileSize());
            material.setParseStatus(MaterialParseStatus.PARSING);
            material.setParseProgressPercent(60);
            material.setParseStage("生成索引");
            material.setParseMessage("正在生成知识片段和向量索引");
            material.setSummaryStatus(MaterialSummaryStatus.PENDING);
            List<ChunkDraft> chunks = chunkMaterial(parsed);
            updateMaterialParseProgress(material.getId(), material.getOwnerId(), 72, "生成预览", "正在生成阅读预览信息");
            applyPreviewMetadata(material, finalPath, sourceType);
            material.setChunkCount(chunks.size());
            learningMaterialRepository.save(material);
            updateMaterialParseProgress(material.getId(), material.getOwnerId(), 82, "写入向量库", "正在保存知识片段和向量");
            // 重新解析时先清理旧索引和旧切片，再写入新切片，保证数据库和 Vector Store 不混用两版内容。
            vectorStoreClient.deleteMaterial(material.getOwnerId(), material.getId());
            materialPageTextBlockRepository.deleteByMaterialId(material.getId());
            materialChunkRepository.deleteByMaterialId(material.getId());
            boolean deferVectorIndex = shouldDeferVectorIndexing(sourceType, chunks.size());
            saveChunks(material, chunks, true, !deferVectorIndex);
            savePageTextBlocks(material, parsed);
            updateMaterialParseProgress(material.getId(), material.getOwnerId(), 96, "收尾处理", "正在更新导入状态");
            material.setParseStatus(MaterialParseStatus.SUCCESS);
            material.setParseProgressPercent(100);
            material.setParseStage("解析完成");
            material.setParseMessage("资料已经可以用于阅读和问答");
            learningMaterialRepository.save(material);
            session.setUploadedChunks(session.getTotalChunks());
              session.setStatus(MaterialUploadSessionStatus.SUCCESS);
              session.setMaterialId(material.getId());
              session.setErrorMessage(null);
              materialUploadSessionRepository.save(session);
              recordMaterialLog(material.getOwnerId(), "UPLOAD_MATERIAL", material.getId(), material.getTitle(), material.getOriginalName(), material.getFileSize());
              cleanupPartDir(session);
              if (deferVectorIndex) {
                  scheduleVectorIndexRebuildAfterCommit(material.getOwnerId(), material.getId());
              }
        } catch (Exception exception) {
            log.error("Upload session processing failed: sessionId={}, materialId={}", sessionId, session.getMaterialId(), exception);
            MaterialUploadSessionEntity failedSession = materialUploadSessionRepository.findById(sessionId).orElse(null);
            if (failedSession != null) {
                failedSession.setStatus(MaterialUploadSessionStatus.FAILED);
                failedSession.setErrorMessage(exception.getMessage() == null ? "upload processing failed" : exception.getMessage());
                materialUploadSessionRepository.save(failedSession);
                if (failedSession.getMaterialId() != null) {
                    learningMaterialRepository.findByIdAndOwnerId(failedSession.getMaterialId(), failedSession.getOwnerId())
                        .ifPresent(material -> {
                            material.setParseStatus(MaterialParseStatus.FAILED);
                            material.setParseStage("解析失败");
                            material.setParseMessage(failedSession.getErrorMessage());
                            learningMaterialRepository.save(material);
                        });
                }
                deleteStoredAssets(failedSession.getStoragePath());
                deletePreviewFile(failedSession.getStoragePath());
                deleteStoredFile(failedSession.getStoragePath());
                cleanupPartDir(failedSession);
            }
        }
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
        int uploaded = countUploadedParts(session);
        session.setUploadedChunks(uploaded);
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
        Path partDir = partDir(session);
        if (!Files.exists(partDir)) {
            return 0;
        }
        try (var stream = Files.list(partDir)) {
            long count = stream.filter(path -> path.getFileName().toString().endsWith(".part")).count();
            return (int) Math.min(Integer.MAX_VALUE, count);
        } catch (IOException exception) {
            return 0;
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
    private void discardFailedUploadSession(MaterialUploadSessionEntity session) {
        cleanupPartDir(session);
        deleteStoredAssets(session.getStoragePath());
        deletePreviewFile(session.getStoragePath());
        deleteStoredFile(session.getStoragePath());
        materialUploadSessionRepository.delete(session);
        materialUploadSessionRepository.flush();
        if (session.getMaterialId() == null) {
            return;
        }
        learningMaterialRepository.findByIdAndOwnerId(session.getMaterialId(), session.getOwnerId())
            .ifPresent(material -> {
                materialSummaryRepository.deleteByMaterialIdAndUserId(material.getId(), material.getOwnerId());
                ragQuestionSourceRepository.deleteByMaterialId(material.getId());
                vectorStoreClient.deleteMaterial(material.getOwnerId(), material.getId());
                materialPageTextBlockRepository.deleteByMaterialId(material.getId());
                materialChunkRepository.deleteByMaterialId(material.getId());
                learningMaterialRepository.delete(material);
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
     * 保存学习资料到数据库（单次上传模式的最终步骤）。
     *
     * <p>处理流程：解析文本 -> 切分知识片段 -> 生成 Embedding -> 保存到数据库和向量库 -> 记录操作日志。
     *
     * @param ownerId    资料所有者用户 ID
     * @param title      资料标题
     * @param sourceType 来源类型
     * @param originalName 原始文件名
     * @param storagePath  文件存储路径
     * @param sourceUrl    来源 URL
     * @param fileSize     文件大小（字节）
     * @param parsed       解析后的文本内容
     * @return 保存成功的资料响应对象
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
        material.setSummaryStatus(MaterialSummaryStatus.PENDING);
        applyPreviewMetadata(material, storagePath, sourceType);
        material.setChunkCount(chunks.size());
        LearningMaterialEntity saved = learningMaterialRepository.save(material);
        boolean deferVectorIndex = shouldDeferVectorIndexing(sourceType, chunks.size());
        saveChunks(saved, chunks, false, !deferVectorIndex);
        savePageTextBlocks(saved, parsed);
        if (deferVectorIndex) {
            scheduleVectorIndexRebuildAfterCommit(saved.getOwnerId(), saved.getId());
        }
        recordMaterialLog(ownerId, "UPLOAD_MATERIAL", saved.getId(), saved.getTitle(), saved.getOriginalName(), saved.getFileSize());
        return toResponse(saved);
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
        List<MaterialChunkEntity> savedChunks = new ArrayList<>();
        Map<Long, List<Double>> embeddingsByChunkId = new LinkedHashMap<>();
        for (int index = 0; index < chunks.size(); index++) {
            ChunkDraft draft = chunks.get(index);
            MaterialChunkEntity chunk = new MaterialChunkEntity();
            chunk.setMaterialId(material.getId());
            chunk.setChunkIndex(index);
            chunk.setChunkText(draft.text());
            chunk.setPageNo(draft.pageNo());
            chunk.setSectionTitle(draft.sectionTitle() == null || draft.sectionTitle().isBlank()
                ? "第" + (index + 1) + "切片"
                : draft.sectionTitle());
            chunk.setHierarchyPath(buildHierarchyPath(material, chunk, index));
            chunk.setSummary(buildChunkSummary(draft.text()));
            chunk.setKeywords(buildChunkKeywords(draft.text()));
            // Embedding 先落库为 JSON，随后解析成向量批量写入 Vector Store，便于后续重建索引。
            String embeddingJson = buildVectorsNow ? toEmbeddingJson(draft.text()) : null;
            chunk.setEmbeddingJson(embeddingJson);
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
                updateMaterialParseProgress(material.getId(), material.getOwnerId(), Math.min(percent, 92), "写入向量库",
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
     * <p>TXT/MD/HTML/WEB 经常会被切成大量短片段；如果同步逐段请求 Embedding，
     * 用户会看到上传卡在最后阶段。这里先保证资料可阅读、可按关键词问答，再后台补语义向量。</p>
     */
    private boolean shouldDeferVectorIndexing(MaterialSourceType sourceType, int chunkCount) {
        if (chunkCount <= 0) {
            return false;
        }
        boolean textLike = sourceType == MaterialSourceType.TXT
            || sourceType == MaterialSourceType.MD
            || sourceType == MaterialSourceType.HTML
            || sourceType == MaterialSourceType.WEB;
        return textLike || chunkCount >= DEFER_VECTOR_INDEX_CHUNK_THRESHOLD;
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
        Runnable task = () -> uploadExecutor.submit(() -> rebuildMaterialVectorIndex(ownerId, materialId));
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
                    chunk.setEmbeddingJson(embeddingJson);
                    materialChunkRepository.save(chunk);
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
     * <p>当前旧解析器只能稳定提供页级文本，因此先把每个 ParsedBlock 保存为整页文本块。
     * MinerU 接入后可写入更细粒度的 bbox 行/段落块；即使 bbox 为空，前端也能在页面内展示
     * 兜底文本层，从而取消页面下方重复解析正文。
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
                MaterialPageTextBlockEntity block = new MaterialPageTextBlockEntity();
                block.setMaterialId(material.getId());
                block.setPageNo(pageNo);
                block.setBlockIndex(blockIndexByPage.merge(pageNo, 1, Integer::sum) - 1);
                block.setText(text);
                block.setBlockType(normalizeText(draft.blockType(), "paragraph"));
                block.setSource(normalizeText(draft.source(), "MINERU"));
                List<Long> pageChunkIds = chunkIdsByPage.getOrDefault(pageNo, List.of());
                block.setChunkId(pageChunkIds.isEmpty() ? null : pageChunkIds.get(0));
                block.setPageWidth(draft.pageWidth());
                block.setPageHeight(draft.pageHeight());
                block.setBboxX(draft.bboxX());
                block.setBboxY(draft.bboxY());
                block.setBboxWidth(draft.bboxWidth());
                block.setBboxHeight(draft.bboxHeight());
                block.setConfidence(draft.confidence());
                blocks.add(block);
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
            MaterialPageTextBlockEntity block = new MaterialPageTextBlockEntity();
            block.setMaterialId(material.getId());
            block.setPageNo(pageNo);
            block.setBlockIndex(blockIndexByPage.merge(pageNo, 1, Integer::sum) - 1);
            block.setText(text);
            block.setBlockType("paragraph");
            block.setSource("LEGACY");
            List<Long> pageChunkIds = chunkIdsByPage.getOrDefault(pageNo, List.of());
            block.setChunkId(pageChunkIds.isEmpty() ? null : pageChunkIds.get(0));
            blocks.add(block);
        }
        if (!blocks.isEmpty()) {
            materialPageTextBlockRepository.saveAll(blocks);
        }
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
                material.setParseProgressPercent(clampProgress(percent));
                material.setParseStage(stage);
                material.setParseMessage(message);
                learningMaterialRepository.save(material);
            });
    }

    private int clampProgress(int percent) {
        return Math.max(0, Math.min(100, percent));
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
            session.getStatus() == null ? null : session.getStatus().name(),
            session.getErrorMessage(),
            material == null ? null : material.getParseProgressPercent(),
            material == null ? null : material.getParseStage(),
            material == null ? null : material.getParseMessage(),
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
     * <p>页面文本层用于阅读器原位划词。MinerU 可提供精确坐标；旧解析器只有页级文本时也会返回
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
            if (shouldUseMineru(sourceType, storedFile)) {
                try {
                    reportProgress(progressListener, 24, "MinerU 解析", "正在调用 MinerU 生成原文文本层");
                    return parseWithMineru(sourceType, storedFile, progressListener);
                } catch (Exception exception) {
                    if (!mineruFallbackEnabled) {
                        throw exception;
                    }
                    log.warn("MinerU parsing failed for {}, falling back to legacy parser", storedFile, exception);
                    reportProgress(progressListener, 28, "内置解析", "MinerU 不可用，已切换到内置解析器");
                }
            }
            return switch (sourceType) {
                case TXT, MD, HTML, WEB -> ParsedMaterial.single(readTextFile(storedFile));
                case DOCX -> parseWord(storedFile);
                case WORD -> parseLegacyWord(storedFile, progressListener);
                case PPTX, PPT -> parsePowerPoint(storedFile);
                case XLSX -> throw new BusinessException(400, "XLSX 文件需要启用 MinerU 解析后才能上传");
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

    private boolean shouldUseMineru(MaterialSourceType sourceType, Path storedFile) {
        if (!"mineru".equalsIgnoreCase(documentParserProvider)) {
            return false;
        }
        if (sourceType == MaterialSourceType.PDF && mineruSkipTextPdf && pdfHasExtractableText(storedFile)) {
            log.info("Skip MinerU for text-based PDF {}, PDF.js/PDFBox text layer is enough for selectable reading", storedFile);
            return false;
        }
        return sourceType == MaterialSourceType.PDF
            || sourceType == MaterialSourceType.DOCX
            || sourceType == MaterialSourceType.WORD
            || sourceType == MaterialSourceType.PPTX
            || sourceType == MaterialSourceType.PPT
            || sourceType == MaterialSourceType.XLSX;
    }

    /**
     * 快速判断 PDF 是否已经包含可抽取文本。
     *
     * <p>普通文本 PDF 可以直接用 PDFBox 生成问答分块，并由前端 PDF.js 提供原生文本层，
     * 不需要先等待 MinerU。扫描件或无法抽取文本的 PDF 会返回 false，继续交给 MinerU/OCR
     * 生成阅读器可划选的后端文本层。
     */
    private boolean pdfHasExtractableText(Path pdfFile) {
        if (pdfFile == null || !Files.exists(pdfFile)) {
            return false;
        }
        try (var document = Loader.loadPDF(pdfFile.toFile())) {
            int pageCount = document.getNumberOfPages();
            if (pageCount <= 0) {
                return false;
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            int detectPages = Math.min(pageCount, mineruTextPdfDetectPages);
            for (int pageNo = 1; pageNo <= detectPages; pageNo++) {
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String text = stripper.getText(document);
                if (text != null && !text.trim().isBlank()) {
                    return true;
                }
            }
        } catch (IOException exception) {
            log.debug("Unable to inspect PDF text layer before MinerU routing: {}", pdfFile, exception);
        }
        return false;
    }

    private ParsedMaterial parseWithMineru(
        MaterialSourceType sourceType,
        Path storedFile,
        ParseProgressListener progressListener
    ) throws IOException, InterruptedException {
        Path mineruInput = storedFile;
        if (sourceType == MaterialSourceType.WORD || sourceType == MaterialSourceType.PPT) {
            Path convertedPdf = convertOfficeToPdf(storedFile);
            if (convertedPdf == null) {
                throw new BusinessException(400, "旧版 Office 文件需要先转换为 PDF 才能交给 MinerU 解析");
            }
            mineruInput = convertedPdf;
        }
        Files.createDirectories(mineruWorkspace);
        Path runDir = mineruWorkspace.resolve("mineru-" + UUID.randomUUID()).normalize();
        if (!runDir.startsWith(mineruWorkspace)) {
            throw new BusinessException(500, "invalid MinerU workspace");
        }
        Files.createDirectories(runDir);
        mineruInput = prepareMineruInput(mineruInput, runDir);
        List<String> command = buildMineruCommand(mineruInput, runDir);
        ProcessBuilder processBuilder = new ProcessBuilder(command)
            .redirectErrorStream(true);
        if (mineruModelSource != null) {
            processBuilder.environment().put("MINERU_MODEL_SOURCE", mineruModelSource);
        }
        Process process = processBuilder.start();
        CompletableFuture<String> outputFuture = readProcessOutputAsync(process);
        boolean finished = process.waitFor(mineruTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new BusinessException(500, "MinerU parsing timed out");
        }
        String output = processOutput(outputFuture);
        if (process.exitValue() != 0) {
            log.warn("MinerU parsing failed exitCode={} command={} output={}", process.exitValue(), command, truncateProcessOutput(output));
            throw new BusinessException(500, "MinerU parsing failed: " + truncateProcessOutput(output));
        }
        reportProgress(progressListener, 42, "MinerU 解析", "正在读取 MinerU 输出文本层");
        ParsedMaterial parsed = readMineruParsedMaterial(runDir);
        if (parsed.isBlank()) {
            throw new BusinessException(400, "MinerU did not extract readable text");
        }
        return parsed;
    }

    private Path prepareMineruInput(Path sourceFile, Path runDir) throws IOException {
        String extension = extensionOf(sourceFile.getFileName().toString());
        if (extension.isBlank()) {
            extension = "bin";
        }
        Path inputDir = runDir.resolve("input").normalize();
        if (!inputDir.startsWith(runDir)) {
            throw new BusinessException(500, "invalid MinerU input path");
        }
        Files.createDirectories(inputDir);
        Path shortInput = inputDir.resolve("input." + extension).normalize();
        if (!shortInput.startsWith(inputDir)) {
            throw new BusinessException(500, "invalid MinerU input file");
        }
        Files.copy(sourceFile, shortInput, StandardCopyOption.REPLACE_EXISTING);
        return shortInput;
    }

    private List<String> buildMineruCommand(Path input, Path outputDir) {
        List<String> command = new ArrayList<>(tokenizeCommandTemplate(mineruCommand));
        if (command.isEmpty()) {
            command.add("mineru");
        }
        command.add("-p");
        command.add(input.toString());
        command.add("-o");
        command.add(outputDir.toString());
        return command;
    }

    private ParsedMaterial readMineruParsedMaterial(Path runDir) throws IOException {
        List<PageTextLayerDraft> textLayers = new ArrayList<>();
        textLayers.addAll(readMineruContentList(findMineruOutputFile(runDir, "content_list_v2.json"), true));
        if (textLayers.isEmpty()) {
            textLayers.addAll(readMineruContentList(findMineruOutputFile(runDir, "content_list.json"), false));
        }
        if (textLayers.isEmpty()) {
            textLayers.addAll(readMineruMiddleJson(findMineruOutputFile(runDir, "middle.json")));
        }
        List<ParsedBlock> blocks = textLayers.stream()
            .filter(layer -> layer.text() != null && !layer.text().isBlank())
            .map(layer -> new ParsedBlock(layer.text(), layer.pageNo(), layer.blockType()))
            .toList();
        if (blocks.isEmpty()) {
            blocks = readMineruMarkdownBlocks(runDir);
        }
        return new ParsedMaterial(blocks, textLayers);
    }

    private Path findMineruOutputFile(Path runDir, String fileName) throws IOException {
        if (runDir == null || !Files.exists(runDir)) {
            return null;
        }
        try (Stream<Path> paths = Files.walk(runDir)) {
            return paths
                .filter(path -> Files.isRegularFile(path) && isMineruOutputFileName(path.getFileName().toString(), fileName))
                .sorted((left, right) -> left.toString().compareToIgnoreCase(right.toString()))
                .findFirst()
                .orElse(null);
        }
    }

    private boolean isMineruOutputFileName(String actualName, String expectedName) {
        if (actualName == null || expectedName == null) {
            return false;
        }
        String normalizedActual = actualName.toLowerCase(Locale.ROOT);
        String normalizedExpected = expectedName.toLowerCase(Locale.ROOT);
        return normalizedActual.equals(normalizedExpected)
            || normalizedActual.endsWith("_" + normalizedExpected);
    }

    private List<PageTextLayerDraft> readMineruContentList(Path path, boolean groupedByPage) throws IOException {
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(path.toFile());
        List<PageTextLayerDraft> result = new ArrayList<>();
        if (!root.isArray()) {
            return result;
        }
        if (groupedByPage && root.size() > 0 && root.get(0).has("page_idx") && root.get(0).has("items")) {
            for (JsonNode page : root) {
                int pageNo = pageIndexToPageNo(page.get("page_idx"));
                JsonNode items = page.get("items");
                if (items != null && items.isArray()) {
                    appendMineruContentItems(result, items, pageNo);
                }
            }
            return result;
        }
        appendMineruContentItems(result, root, null);
        return result;
    }

    private void appendMineruContentItems(List<PageTextLayerDraft> result, JsonNode items, Integer defaultPageNo) {
        int localIndex = 0;
        for (JsonNode item : items) {
            String text = mineruItemText(item);
            if (text.isBlank()) {
                continue;
            }
            Integer pageNo = defaultPageNo != null ? defaultPageNo : mineruItemPageNo(item);
            double[] bbox = mineruBbox(item.get("bbox"));
            result.add(new PageTextLayerDraft(
                pageNo == null || pageNo <= 0 ? 1 : pageNo,
                localIndex++,
                text,
                normalizeText(item.path("type").asText(null), "paragraph"),
                "MINERU",
                MINERU_NORMALIZED_PAGE_SIZE,
                MINERU_NORMALIZED_PAGE_SIZE,
                bbox == null ? null : bbox[0],
                bbox == null ? null : bbox[1],
                bbox == null ? null : Math.max(1.0, bbox[2] - bbox[0]),
                bbox == null ? null : Math.max(1.0, bbox[3] - bbox[1]),
                null
            ));
        }
    }

    private List<PageTextLayerDraft> readMineruMiddleJson(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(path.toFile());
        JsonNode pages = root.has("pdf_info") ? root.get("pdf_info") : root.get("pages");
        if (pages == null || !pages.isArray()) {
            return List.of();
        }
        List<PageTextLayerDraft> result = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            JsonNode page = pages.get(pageIndex);
            int pageNo = page.has("page_idx") ? pageIndexToPageNo(page.get("page_idx")) : pageIndex + 1;
            double pageWidth = firstNumber(page, MINERU_NORMALIZED_PAGE_SIZE, "width", "page_width", "w");
            double pageHeight = firstNumber(page, MINERU_NORMALIZED_PAGE_SIZE, "height", "page_height", "h");
            JsonNode blocks = firstArray(page, "para_blocks", "preproc_blocks", "blocks");
            if (blocks != null) {
                appendMineruMiddleBlocks(result, blocks, pageNo, pageWidth, pageHeight);
            }
        }
        return result;
    }

    private void appendMineruMiddleBlocks(
        List<PageTextLayerDraft> result,
        JsonNode blocks,
        int pageNo,
        double pageWidth,
        double pageHeight
    ) {
        int blockIndex = 0;
        for (JsonNode block : blocks) {
            String text = mineruMiddleBlockText(block);
            if (text.isBlank()) {
                continue;
            }
            double[] bbox = mineruBbox(block.get("bbox"));
            result.add(new PageTextLayerDraft(
                pageNo,
                blockIndex++,
                text,
                normalizeText(block.path("type").asText(null), "paragraph"),
                "MINERU",
                pageWidth,
                pageHeight,
                bbox == null ? null : bbox[0],
                bbox == null ? null : bbox[1],
                bbox == null ? null : Math.max(1.0, bbox[2] - bbox[0]),
                bbox == null ? null : Math.max(1.0, bbox[3] - bbox[1]),
                null
            ));
        }
    }

    private List<ParsedBlock> readMineruMarkdownBlocks(Path runDir) throws IOException {
        Path markdown = null;
        try (Stream<Path> paths = Files.walk(runDir)) {
            markdown = paths
                .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                .findFirst()
                .orElse(null);
        }
        if (markdown == null) {
            return List.of();
        }
        String text = readTextFile(markdown).trim();
        return text.isBlank() ? List.of() : List.of(new ParsedBlock(text, 1, "MinerU Markdown"));
    }

    private String mineruItemText(JsonNode item) {
        StringBuilder builder = new StringBuilder();
        appendNodeText(builder, item.get("text"));
        appendNodeText(builder, item.get("content"));
        appendMineruHtmlText(builder, item.get("table_body"));
        appendMineruHtmlText(builder, item.get("html"));
        JsonNode lines = item.get("lines");
        if (lines != null && lines.isArray()) {
            for (JsonNode line : lines) {
                appendNodeText(builder, line.get("text"));
            }
        }
        return normalizePageText(builder.toString());
    }

    private String mineruMiddleBlockText(JsonNode block) {
        StringBuilder builder = new StringBuilder();
        appendNodeText(builder, block.get("text"));
        JsonNode lines = firstArray(block, "lines", "spans");
        if (lines != null) {
            for (JsonNode line : lines) {
                appendNodeText(builder, line.get("text"));
                JsonNode spans = line.get("spans");
                if (spans != null && spans.isArray()) {
                    for (JsonNode span : spans) {
                        appendNodeText(builder, span.get("content"));
                        appendNodeText(builder, span.get("text"));
                    }
                }
            }
        }
        return normalizePageText(builder.toString());
    }

    private void appendNodeText(StringBuilder builder, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            appendText(builder, node.asText());
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                appendNodeText(builder, item);
            }
        }
    }

    private void appendMineruHtmlText(StringBuilder builder, JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isTextual()) {
            appendText(builder, cleanWebText(node.asText()));
            return;
        }
        appendNodeText(builder, node);
    }

    private void appendText(StringBuilder builder, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(text);
    }

    private String normalizePageText(String text) {
        String normalized = text == null ? "" : text
            .replaceAll("\\s+", " ")
            .trim();
        if (normalized.length() <= PAGE_TEXT_BLOCK_MAX_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, PAGE_TEXT_BLOCK_MAX_LENGTH).trim();
    }

    private Integer mineruItemPageNo(JsonNode item) {
        if (item == null) {
            return null;
        }
        if (item.has("page_no")) {
            return item.get("page_no").asInt();
        }
        if (item.has("page")) {
            int page = item.get("page").asInt();
            return page <= 0 ? 1 : page;
        }
        if (item.has("page_idx")) {
            return pageIndexToPageNo(item.get("page_idx"));
        }
        return null;
    }

    private int pageIndexToPageNo(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return 1;
        }
        return Math.max(1, node.asInt() + 1);
    }

    private double[] mineruBbox(JsonNode bbox) {
        if (bbox == null || !bbox.isArray() || bbox.size() < 4) {
            return null;
        }
        double x1 = bbox.get(0).asDouble();
        double y1 = bbox.get(1).asDouble();
        double x2 = bbox.get(2).asDouble();
        double y2 = bbox.get(3).asDouble();
        if (!Double.isFinite(x1) || !Double.isFinite(y1) || !Double.isFinite(x2) || !Double.isFinite(y2)) {
            return null;
        }
        return new double[] { x1, y1, x2, y2 };
    }

    private JsonNode firstArray(JsonNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isArray()) {
                return value;
            }
        }
        return null;
    }

    private double firstNumber(JsonNode node, double fallback, String... names) {
        if (node == null) {
            return fallback;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) {
                return value.asDouble();
            }
        }
        return fallback;
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
        List<ParsedBlock> blocks = new ArrayList<>();
        List<PageTextLayerDraft> textLayers = new ArrayList<>();
        try (var document = Loader.loadPDF(pdfFile.toFile())) {
            int pageCount = document.getNumberOfPages();
            boolean inlinePdfOcrEnabled = shouldInlinePdfOcr(pdfFile, pageCount);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
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
        }
        return new ParsedMaterial(blocks, textLayers);
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
     * 让 MinerU 不可用时扫描 PDF 仍然可以按行划词。精准坐标仍优先依赖 MinerU 输出的 bbox。
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
        double pageWidth = pageBox == null ? MINERU_NORMALIZED_PAGE_SIZE : Math.max(1.0, pageBox.getWidth());
        double pageHeight = pageBox == null ? MINERU_NORMALIZED_PAGE_SIZE : Math.max(1.0, pageBox.getHeight());
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
            for (String text : chunkText(block.text())) {
                chunks.add(new ChunkDraft(text, block.pageNo(), block.sectionTitle()));
            }
        }
        return chunks;
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
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            return List.of("");
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
            for (int index = 0; index < chunks.size(); index++) {
                MaterialChunkEntity chunk = chunks.get(index);
                if (chunk.getId() == null) {
                    continue;
                }
                int pageNo = Math.min(pageCount, Math.max(1, (index * pageCount) / chunks.size() + 1));
                result.computeIfAbsent(pageNo, ignored -> new ArrayList<>()).add(chunk.getId());
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
    private record ParsedMaterial(List<ParsedBlock> blocks, List<PageTextLayerDraft> textLayers) {
        private ParsedMaterial(List<ParsedBlock> blocks) {
            this(blocks, List.of());
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
