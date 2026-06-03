package com.mytext.learningassistant.material;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.http.HttpHeaders;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.admin.UsageRecordEntity;
import com.mytext.learningassistant.admin.UsageRecordRepository;
import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.embedding.EmbeddingClient;
import com.mytext.learningassistant.rag.MaterialSummaryRepository;
import com.mytext.learningassistant.rag.RagQuestionSourceRepository;
import com.mytext.learningassistant.vector.VectorStoreClient;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PreDestroy;

@Service
public class MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int CHUNK_MIN_SIZE = 300;
    private static final int CHUNK_MAX_SIZE = 800;
    private static final int CHUNK_OVERLAP = 120;
    private static final long MAX_MATERIAL_BYTES = 500L * 1024L * 1024L;
    private static final long MAX_WEB_BYTES = MAX_MATERIAL_BYTES;
    private static final Duration WEB_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String PART_SUFFIX = ".parts";
    private static final String ASSET_SUFFIX = ".assets";
    private static final String IMAGE_MARKER_PREFIX = "[[material-image:";
    private static final String IMAGE_MARKER_SUFFIX = "]]";
    private static final String PREVIEW_SUFFIX = ".preview.pdf";
    private static final String PAGE_IMAGE_RE = "^page-(\\d+)(?:-\\d+)?\\.png$";
    private static final int DEFAULT_RENDER_DPI = 144;
    private static final long DEFAULT_INLINE_PDF_OCR_MAX_BYTES = 100L * 1024L * 1024L;
    private static final int DEFAULT_INLINE_PDF_OCR_MAX_PAGES = 200;
    private static final long DEFAULT_PDF_COMPRESSION_MIN_BYTES = 64L * 1024L * 1024L;
    private static final int DEFAULT_PDF_COMPRESSION_TARGET_DPI = 144;
    private static final int EMBEDDING_CACHE_MAX_ENTRIES = 2_048;
    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9+#./-]{2,}");
    private static final List<String> KEYWORD_STOP_WORDS = List.of(
        "the", "and", "for", "with", "that", "this", "from", "into", "are", "was", "were", "has", "have",
        "what", "how", "why", "where", "which", "does", "do", "did", "can", "could", "would", "should",
        "什么", "怎么", "为什么", "哪里", "哪个", "哪些", "一个", "这个", "那个", "以及", "如果", "因为"
    );

    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final MaterialSummaryRepository materialSummaryRepository;
    private final RagQuestionSourceRepository ragQuestionSourceRepository;
    private final MaterialUploadSessionRepository materialUploadSessionRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final ObjectMapper objectMapper;
    private final EmbeddingClient embeddingClient;
    private final VectorStoreClient vectorStoreClient;
    private final HttpClient httpClient;
    private final Path storageRoot;
    private final boolean ocrEnabled;
    private final String ocrLang;
    private final String ocrCommand;
    private final String ocrCommandTemplate;
    private final Duration ocrTimeout;
    private final boolean converterEnabled;
    private final String converterCommand;
    private final Duration converterTimeout;
    private final int renderDpi;
    private final long inlinePdfOcrMaxBytes;
    private final int inlinePdfOcrMaxPages;
    private final boolean pdfCompressionEnabled;
    private final String pdfCompressionCommand;
    private final String pdfCompressionCommandTemplate;
    private final Duration pdfCompressionTimeout;
    private final long pdfCompressionMinBytes;
    private final int pdfCompressionTargetDpi;
    private final ExecutorService uploadExecutor = Executors.newFixedThreadPool(2);
    private final Semaphore renderSemaphore = new Semaphore(2);
    private final ConcurrentMap<String, String> embeddingJsonCache = new ConcurrentHashMap<>();

    public MaterialService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        MaterialSummaryRepository materialSummaryRepository,
        RagQuestionSourceRepository ragQuestionSourceRepository,
        MaterialUploadSessionRepository materialUploadSessionRepository,
        UsageRecordRepository usageRecordRepository,
        ObjectMapper objectMapper,
        EmbeddingClient embeddingClient,
        VectorStoreClient vectorStoreClient,
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
        @Value("${app.pdf.compression.target-dpi:144}") int pdfCompressionTargetDpi
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.materialSummaryRepository = materialSummaryRepository;
        this.ragQuestionSourceRepository = ragQuestionSourceRepository;
        this.materialUploadSessionRepository = materialUploadSessionRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.objectMapper = objectMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStoreClient = vectorStoreClient;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
        this.ocrEnabled = ocrEnabled;
        this.ocrLang = ocrLang == null || ocrLang.isBlank() ? "eng+chi_sim" : ocrLang.trim();
        this.ocrCommand = ocrCommand == null || ocrCommand.isBlank() ? "tesseract" : ocrCommand.trim();
        this.ocrCommandTemplate = normalizeOptionalText(ocrCommandTemplate);
        this.ocrTimeout = ocrTimeout == null ? Duration.ofSeconds(20) : ocrTimeout;
        this.converterEnabled = converterEnabled;
        this.converterCommand = converterCommand == null || converterCommand.isBlank() ? "soffice" : converterCommand.trim();
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
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(WEB_REQUEST_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @PreDestroy
    public void shutdown() {
        uploadExecutor.shutdownNow();
    }

    @Transactional
    public MaterialResponse upload(long ownerId, String title, String sourceTypeValue, MultipartFile file, String sourceUrl) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(400, "file cannot be empty");
        }
        if (file.getSize() > MAX_MATERIAL_BYTES) {
            throw new BusinessException(400, "file is too large");
        }
        MaterialSourceType sourceType = parseSourceType(sourceTypeValue, file.getOriginalFilename());
        String originalName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        rejectLegacyOfficeFile(originalName);
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
            validateUploadSessionMetadata(existing, title, originalName, sourceType, sourceUrl, fileSize, chunkSize, totalChunks);
            return toUploadSessionResponse(existing);
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

    @Transactional(readOnly = true)
    public MaterialUploadSessionResponse getUploadSession(long ownerId, String sessionId) {
        return toUploadSessionResponse(requireUploadSession(ownerId, sessionId));
    }

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
        Path partPath = partPath(session, chunkIndex);
        try {
            Files.createDirectories(partPath.getParent());
            byte[] bytes = chunk.getBytes();
            String expectedChecksum = normalizeOptionalText(checksumSha256);
            if (expectedChecksum != null && !expectedChecksum.isBlank()) {
                String actualChecksum = sha256(bytes);
                if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                    throw new BusinessException(400, "chunk checksum mismatch");
                }
            }
            if (Files.exists(partPath)) {
                byte[] existing = Files.readAllBytes(partPath);
                if (existing.length == bytes.length && sha256(existing).equalsIgnoreCase(sha256(bytes))) {
                    refreshSessionProgress(session);
                    return toUploadSessionResponse(session);
                }
                throw new BusinessException(409, "Chunk content mismatch");
            }
            Files.write(partPath, bytes);
        } catch (IOException exception) {
            throw new BusinessException(500, "failed to save chunk");
        }

        refreshSessionProgress(session);
        if (session.getUploadedChunks() != null && session.getTotalChunks() != null
            && session.getUploadedChunks() >= session.getTotalChunks()
            && session.getStatus() == MaterialUploadSessionStatus.UPLOADING) {
            session.setStatus(MaterialUploadSessionStatus.PROCESSING);
            materialUploadSessionRepository.save(session);
            markMaterialParsing(session);
            scheduleProcessing(session.getSessionId());
        }
        return toUploadSessionResponse(session);
    }

    public List<MaterialResponse> list(long ownerId) {
        return learningMaterialRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public MaterialDetailResponse detail(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        return toDetailResponse(material);
    }

    @Transactional(readOnly = true)
    public List<MaterialChunkResponse> chunks(long ownerId, long materialId) {
        learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        return materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId)
            .stream()
            .map(this::toChunkResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<MaterialPageResponse> pages(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        Path previewPath = previewPdfPath(material);
        if (previewPath == null || !Files.exists(previewPath) || !Files.isRegularFile(previewPath)) {
            return List.of();
        }
        Map<Integer, List<Long>> pageChunks = chunksByPage(materialId);
        try (var document = Loader.loadPDF(previewPath.toFile())) {
            List<MaterialPageResponse> pages = new ArrayList<>();
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
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
                contentTypeFor(material.getSourceType(), material.getOriginalName()),
                Files.size(path)
            );
        } catch (IOException exception) {
            throw new BusinessException(500, "failed to read material file");
        }
    }

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

        ParsedMaterial parsed = parseMaterial(material.getSourceType(), sourcePath);
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
        materialChunkRepository.deleteByMaterialId(materialId);
        saveChunks(material, chunks, true);

        updateMaterialParseProgress(material.getId(), material.getOwnerId(), 92, "生成预览", "正在生成阅读预览信息");
        applyPreviewMetadata(material, sourcePath, material.getSourceType());
        material.setParseStatus(MaterialParseStatus.SUCCESS);
        material.setParseProgressPercent(100);
        material.setParseStage("解析完成");
        material.setParseMessage("资料已经可以用于阅读和问答");
        material.setSummaryStatus(MaterialSummaryStatus.PENDING);
        material.setChunkCount(chunks.size());
        return toDetailResponse(learningMaterialRepository.save(material));
    }

    @Transactional
    public void delete(long ownerId, long materialId) {
        LearningMaterialEntity material = learningMaterialRepository.findByIdAndOwnerId(materialId, ownerId)
            .orElseThrow(() -> new BusinessException(404, "Material not found"));
        materialSummaryRepository.deleteByMaterialIdAndUserId(materialId, ownerId);
        ragQuestionSourceRepository.deleteByMaterialId(materialId);
        vectorStoreClient.deleteMaterial(ownerId, materialId);
        materialChunkRepository.deleteByMaterialId(materialId);
        learningMaterialRepository.delete(material);
        deleteStoredAssets(material.getStoragePath());
        deleteStoredFile(material.getStoragePath());
        deletePreviewFile(material.getStoragePath());
    }

    private void scheduleProcessing(String sessionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
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
            assembleUploadedFile(session, finalPath);
            updateMaterialParseProgress(session, 18, "校验文件", "正在校验文件完整性");
            validateUploadedFileChecksum(session, finalPath);
            MaterialSourceType sourceType = session.getSourceType();
            updateMaterialParseProgress(session, 30, "提取文本", "正在从文件中提取可检索文本");
            ParsedMaterial parsed = parseMaterial(sourceType, finalPath);
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
            vectorStoreClient.deleteMaterial(material.getOwnerId(), material.getId());
            materialChunkRepository.deleteByMaterialId(material.getId());
            saveChunks(material, chunks, true);
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

    private void assembleUploadedFile(MaterialUploadSessionEntity session, Path finalPath) throws IOException {
        Path partDir = partDir(session);
        try (var output = Files.newOutputStream(finalPath)) {
            for (int index = 0; index < session.getTotalChunks(); index++) {
                Path partPath = partDir.resolve(partFileName(index));
                if (!Files.exists(partPath)) {
                    throw new BusinessException(400, "Missing upload chunk");
                }
                Files.copy(partPath, output);
                Files.deleteIfExists(partPath);
            }
        }
    }

    private void refreshSessionProgress(MaterialUploadSessionEntity session) {
        int uploaded = countUploadedParts(session);
        session.setUploadedChunks(uploaded);
        materialUploadSessionRepository.save(session);
    }

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

    private MaterialUploadSessionEntity requireUploadSession(long ownerId, String sessionId) {
        return materialUploadSessionRepository.findById(sessionId)
            .filter(session -> session.getOwnerId() != null && session.getOwnerId() == ownerId)
            .orElseThrow(() -> new BusinessException(404, "Upload session not found"));
    }

    private void validateUploadSessionMetadata(
        MaterialUploadSessionEntity session,
        String title,
        String originalName,
        MaterialSourceType sourceType,
        String sourceUrl,
        long fileSize,
        int chunkSize,
        int totalChunks
    ) {
        boolean same =
            Objects.equals(normalizeText(session.getTitle(), ""), title)
                && Objects.equals(normalizeText(session.getOriginalName(), ""), originalName)
                && session.getSourceType() == sourceType
                && Objects.equals(normalizeOptionalText(session.getSourceUrl()), sourceUrl)
                && Objects.equals(session.getFileSize(), fileSize)
                && Objects.equals(session.getChunkSize(), chunkSize)
                && Objects.equals(session.getTotalChunks(), totalChunks);
        if (!same) {
            throw new BusinessException(409, "Upload metadata mismatch");
        }
    }

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
        saveChunks(saved, chunks, false);
        recordMaterialLog(ownerId, "UPLOAD_MATERIAL", saved.getId(), saved.getTitle(), saved.getOriginalName(), saved.getFileSize());
        return toResponse(saved);
    }

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

    private void saveChunks(LearningMaterialEntity material, List<ChunkDraft> chunks, boolean updateProgress) {
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
            String embeddingJson = toEmbeddingJson(draft.text());
            chunk.setEmbeddingJson(embeddingJson);
            chunk.setCreatedAt(java.time.LocalDateTime.now());
            MaterialChunkEntity saved = materialChunkRepository.save(chunk);
            savedChunks.add(saved);
            List<Double> embedding = parseEmbeddingJson(embeddingJson);
            if (embedding != null && saved.getId() != null) {
                embeddingsByChunkId.put(saved.getId(), embedding);
            }
            if (updateProgress && chunks.size() > 0 && (index == chunks.size() - 1 || index % 5 == 0)) {
                int percent = 82 + (int) Math.floor(((index + 1) * 10.0) / chunks.size());
                updateMaterialParseProgress(material.getId(), material.getOwnerId(), Math.min(percent, 92), "写入向量库",
                    "正在保存知识片段 " + (index + 1) + "/" + chunks.size());
            }
        }
        vectorStoreClient.upsertChunks(material.getOwnerId(), material, savedChunks, embeddingsByChunkId);
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

    private String excerpt(String text) {
        String normalized = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= 160) {
            return normalized;
        }
        return normalized.substring(0, 160) + "...";
    }

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
        if (lowerName.endsWith(".docx")) {
            return MaterialSourceType.DOCX;
        }
        if (lowerName.endsWith(".pptx")) {
            return MaterialSourceType.PPTX;
        }
        if (lowerName.endsWith(".pdf")) {
            return MaterialSourceType.PDF;
        }
        return MaterialSourceType.TXT;
    }

    private Path resolveStoragePath(long ownerId, String originalName) {
        String safeName = originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return storageRoot.resolve(String.valueOf(ownerId)).resolve(UUID.randomUUID() + "_" + safeName);
    }

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

    private String storagePathValue(Path storagePath) {
        Path absolutePath = storagePath.toAbsolutePath().normalize();
        if (absolutePath.startsWith(storageRoot)) {
            return storageRoot.relativize(absolutePath).toString().replace('\\', '/');
        }
        return absolutePath.toString();
    }

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

    private int calculateTotalChunks(long fileSize, int chunkSize) {
        if (chunkSize <= 0) {
            throw new BusinessException(400, "chunkSize must be positive");
        }
        long total = Math.max(1L, (fileSize + chunkSize - 1) / chunkSize);
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private void validateMaterialFileSize(long fileSize) {
        if (fileSize < 0) {
            throw new BusinessException(400, "fileSize cannot be negative");
        }
        if (fileSize > MAX_MATERIAL_BYTES) {
            throw new BusinessException(400, "file is too large");
        }
    }

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

    private void rejectLegacyOfficeFile(String originalName) {
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".doc") || lowerName.endsWith(".ppt")) {
            throw new BusinessException(400, "Legacy .doc/.ppt files are not supported");
        }
    }

    private ParsedMaterial parseMaterial(MaterialSourceType sourceType, Path storedFile) {
        try {
            if (sourceType == MaterialSourceType.PDF
                || sourceType == MaterialSourceType.DOCX
                || sourceType == MaterialSourceType.WORD
                || sourceType == MaterialSourceType.PPTX
                || sourceType == MaterialSourceType.PPT) {
                deleteStoredAssets(storedFile.toString());
            }
            return switch (sourceType) {
                case TXT, MD, HTML, WEB -> ParsedMaterial.single(readTextFile(storedFile));
                case DOCX, WORD -> parseWord(storedFile);
                case PPTX, PPT -> parsePowerPoint(storedFile);
                case PDF -> parsePdf(storedFile, preparePdfProcessingFile(storedFile));
            };
        } catch (Exception exception) {
            log.warn("Material parsing failed for {} ({})", storedFile, sourceType, exception);
            throw new BusinessException(400, "material parsing failed");
        }
    }

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
            boolean finished = process.waitFor(converterTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("Office preview conversion failed for {}: {}", sourcePath, readProcessOutput(process));
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

    private String readProcessOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException exception) {
            return "";
        }
    }

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

    private ParsedMaterial parsePdf(Path sourceFile, Path pdfFile) throws IOException {
        List<ParsedBlock> blocks = new ArrayList<>();
        try (var document = Loader.loadPDF(pdfFile.toFile())) {
            boolean inlinePdfOcrEnabled = shouldInlinePdfOcr(pdfFile, document.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                int pageNo = pageIndex + 1;
                stripper.setStartPage(pageNo);
                stripper.setEndPage(pageNo);
                String pageText = stripper.getText(document);
                String extractedText = pageText == null ? "" : pageText.trim();
                String blockText = extractedText.isBlank()
                    ? "第 " + pageNo + " 页暂无可抽取文本，已保留原页预览用于阅读和问答依据。"
                    : extractedText;
                if (extractedText.isBlank()) {
                    blockText = scannedPdfPageText(sourceFile, document, pageIndex, pageNo, inlinePdfOcrEnabled);
                }
                blocks.add(new ParsedBlock(blockText, pageNo, "Page " + pageNo));
            }
        }
        return new ParsedMaterial(blocks);
    }

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
            boolean finished = process.waitFor(pdfCompressionTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("PDF compression timed out for {}", sourcePath);
                return null;
            }
            if (process.exitValue() != 0) {
                log.warn("PDF compression failed for {}: {}", sourcePath, readProcessOutput(process));
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

    private String scannedPdfPageText(
        Path file,
        org.apache.pdfbox.pdmodel.PDDocument document,
        int pageIndex,
        int pageNo,
        boolean inlinePdfOcrEnabled
    ) {
        String imageName = pageImageName(pageNo);
        String marker = imageMarker(imageName);
        if (!inlinePdfOcrEnabled) {
            return marker + "\n\u7b2c " + pageNo + " \u9875\u6682\u65e0\u53ef\u62bd\u53d6\u6587\u672c\uff1b\u539f\u9875\u56fe\u7247\u5c06\u5728\u9884\u89c8\u6216\u591a\u6a21\u6001\u95ee\u7b54\u65f6\u6309\u9700\u751f\u6210\u3002";
        }
        Path imagePath = renderPdfPageAsset(file, document, pageIndex, imageName);
        String ocrText = imagePath == null ? "" : ocrImageText(imagePath);
        if (!ocrText.isBlank()) {
            return marker + "\n[image ocr: " + imageName + "]\n" + ocrText;
        }
        return marker + "\n\u7b2c " + pageNo + " \u9875\u6682\u65e0\u53ef\u62bd\u53d6\u6587\u672c\uff1b\u5df2\u4fdd\u7559\u539f\u9875\u56fe\u7247\uff0c\u53ef\u7528\u4e8e\u9884\u89c8\u548c\u591a\u6a21\u6001\u95ee\u7b54\u3002";
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

    private List<ChunkDraft> chunkMaterial(ParsedMaterial parsed) {
        List<ChunkDraft> chunks = new ArrayList<>();
        for (ParsedBlock block : parsed.blocks()) {
            for (String text : chunkText(block.text())) {
                chunks.add(new ChunkDraft(text, block.pageNo(), block.sectionTitle()));
            }
        }
        return chunks;
    }

    private List<String> chunkText(String content) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            return List.of("");
        }
        if (normalized.contains(IMAGE_MARKER_PREFIX)) {
            return chunkTextWithImageMarkers(normalized);
        }
        return semanticChunk(normalized);
    }

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
                if (!buffer.isEmpty()) {
                    addChunk(chunks, buffer.toString().trim());
                    buffer.setLength(0);
                }
                chunks.addAll(splitBySentences(trimmed));
                continue;
            }
            if (buffer.length() + trimmed.length() + 1 > CHUNK_MAX_SIZE && !buffer.isEmpty()) {
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

    private void addChunk(List<String> chunks, String text) {
        if (!text.isBlank()) {
            chunks.add(text);
        }
    }

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

    private Map<Integer, List<Long>> chunksByPage(long materialId) {
        Map<Integer, List<Long>> result = new LinkedHashMap<>();
        for (MaterialChunkEntity chunk : materialChunkRepository.findByMaterialIdOrderByChunkIndexAsc(materialId)) {
            if (chunk.getPageNo() == null || chunk.getId() == null) {
                continue;
            }
            result.computeIfAbsent(chunk.getPageNo(), ignored -> new ArrayList<>()).add(chunk.getId());
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
            return null;
        } finally {
            if (acquired) {
                renderSemaphore.release();
            }
        }
    }

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
            boolean finished = process.waitFor(ocrTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "";
            }
            if (process.exitValue() != 0) {
                return "";
            }
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception exception) {
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
            String embeddingJson = embeddingClient.embed(cleanedText)
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
                    embeddingJsonCache.clear();
                }
                embeddingJsonCache.putIfAbsent(cacheKey, embeddingJson);
            }
            return embeddingJson;
        } catch (Exception exception) {
            return null;
        }
    }

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
        return keywords.isBlank() ? null : keywords;
    }

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
        return text;
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }

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

    private static final class ImageCounter {
        private int value;

        private int next() {
            value += 1;
            return value;
        }
    }

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
        return uri;
    }

    private FetchedWebResource fetchWebResource(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(WEB_REQUEST_TIMEOUT)
            .header("User-Agent", "LearningAssistantBot/1.0")
            .GET()
            .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException(400, "Web fetch failed, status: " + response.statusCode());
            }
            byte[] body = response.body();
            if (body.length > MAX_WEB_BYTES) {
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

    private String contentTypeFor(MaterialSourceType sourceType, String originalName) {
        return switch (sourceType) {
            case PDF -> "application/pdf";
            case DOCX, WORD -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case PPTX, PPT -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case MD, TXT -> "text/plain; charset=utf-8";
            case HTML, WEB -> "text/html; charset=utf-8";
        };
    }

    private void appendWordParagraphsLegacy(StringBuilder builder, String xml) {
        appendWordParagraphs(builder, xml);
    }

    private record ParsedMaterial(List<ParsedBlock> blocks) {
        private static ParsedMaterial single(String text) {
            String normalized = text == null ? "" : text.trim();
            return new ParsedMaterial(normalized.isBlank() ? List.of() : List.of(new ParsedBlock(normalized, null, null)));
        }

        private boolean isBlank() {
            return blocks.stream().allMatch(block -> block.text() == null || block.text().isBlank());
        }
    }

    private record ParsedBlock(String text, Integer pageNo, String sectionTitle) {
    }

    private record ChunkDraft(String text, Integer pageNo, String sectionTitle) {
    }

    private record FetchedWebResource(byte[] body, HttpHeaders headers, Charset charset) {
        private String textBody() {
            return new String(body, charset);
        }
    }
}
