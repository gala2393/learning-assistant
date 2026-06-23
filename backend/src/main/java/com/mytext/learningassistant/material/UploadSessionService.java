package com.mytext.learningassistant.material;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mytext.learningassistant.admin.UsageRecordEntity;
import com.mytext.learningassistant.admin.UsageRecordRepository;
import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.rag.MaterialSummaryRepository;
import com.mytext.learningassistant.rag.RagQuestionSourceRepository;
import com.mytext.learningassistant.vector.VectorStoreClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 资料分片上传会话服务。
 *
 * <p>负责创建上传会话、保存分片、刷新分片进度、判断会话是否需要进入后台解析。
 * 解析和切片仍由 MaterialService 执行，这里只返回调度标记，避免两个服务互相依赖。</p>
 */
@Service
public class UploadSessionService {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String PART_SUFFIX = ".parts";
    private static final int CLIENT_UPLOAD_ID_MAX_LENGTH = 64;

    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialUploadSessionRepository materialUploadSessionRepository;
    private final MaterialProcessingJobService materialProcessingJobService;
    private final MaterialDeletionService materialDeletionService;
    private final MaterialSummaryRepository materialSummaryRepository;
    private final RagQuestionSourceRepository ragQuestionSourceRepository;
    private final MaterialPageRepository materialPageRepository;
    private final MaterialPageTextBlockRepository materialPageTextBlockRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final UsageRecordRepository usageRecordRepository;
    private final VectorStoreClient vectorStoreClient;
    private final Path storageRoot;
    private final boolean ocrEnabled;
    private final long maxMaterialBytes;

    public UploadSessionService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialUploadSessionRepository materialUploadSessionRepository,
        MaterialProcessingJobService materialProcessingJobService,
        MaterialDeletionService materialDeletionService,
        MaterialSummaryRepository materialSummaryRepository,
        RagQuestionSourceRepository ragQuestionSourceRepository,
        MaterialPageRepository materialPageRepository,
        MaterialPageTextBlockRepository materialPageTextBlockRepository,
        MaterialChunkRepository materialChunkRepository,
        UsageRecordRepository usageRecordRepository,
        VectorStoreClient vectorStoreClient,
        @Value("${app.storage-dir:${user.dir}/target/learning-assistant-files}") String storageDir,
        @Value("${app.ocr.enabled:false}") boolean ocrEnabled,
        @Value("${app.material.max-file-bytes:2147483648}") long maxMaterialBytes
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialUploadSessionRepository = materialUploadSessionRepository;
        this.materialProcessingJobService = materialProcessingJobService;
        this.materialDeletionService = materialDeletionService;
        this.materialSummaryRepository = materialSummaryRepository;
        this.ragQuestionSourceRepository = ragQuestionSourceRepository;
        this.materialPageRepository = materialPageRepository;
        this.materialPageTextBlockRepository = materialPageTextBlockRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.usageRecordRepository = usageRecordRepository;
        this.vectorStoreClient = vectorStoreClient;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
        this.ocrEnabled = ocrEnabled;
        this.maxMaterialBytes = maxMaterialBytes <= 0 ? 2L * 1024L * 1024L * 1024L : maxMaterialBytes;
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

    @Transactional(readOnly = true)
    public MaterialUploadSessionResponse getUploadSession(long ownerId, String sessionId) {
        return toUploadSessionResponse(requireUploadSession(ownerId, sessionId));
    }

    @Transactional
    public UploadChunkResult uploadChunk(
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
            return new UploadChunkResult(toUploadSessionResponse(session), session.getSessionId(), false);
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
            if (expectedChecksum != null && !expectedChecksum.isBlank()) {
                String actualChecksum = sha256(tempPartPath);
                if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
                    throw new BusinessException(400, "chunk checksum mismatch");
                }
            }
            if (Files.exists(partPath)) {
                if (Files.size(partPath) == Files.size(tempPartPath) && sameFileHash(partPath, tempPartPath)) {
                    refreshSessionProgress(session);
                    return new UploadChunkResult(toUploadSessionResponse(session), session.getSessionId(), false);
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
                // 临时分片清理失败不影响上传主流程。
            }
        }

        refreshSessionProgress(session);
        boolean shouldScheduleProcessing = false;
        if (session.getTotalChunks() != null
            && hasAllUploadedParts(session)
            && session.getStatus() == MaterialUploadSessionStatus.UPLOADING) {
            session.setStatus(MaterialUploadSessionStatus.PROCESSING);
            materialUploadSessionRepository.save(session);
            markMaterialParsing(session);
            shouldScheduleProcessing = true;
        }
        return new UploadChunkResult(toUploadSessionResponse(session), session.getSessionId(), shouldScheduleProcessing);
    }

    public Path resolveStoredPath(String storagePath) {
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

    public String storagePathValue(Path storagePath) {
        Path absolutePath = storagePath.toAbsolutePath().normalize();
        if (absolutePath.startsWith(storageRoot)) {
            return storageRoot.relativize(absolutePath).toString().replace('\\', '/');
        }
        return absolutePath.toString();
    }

    public boolean isCompleteStoredFile(MaterialUploadSessionEntity session, Path finalPath) {
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

    public void assembleUploadedFile(MaterialUploadSessionEntity session, Path finalPath) throws IOException {
        Path partDir = partDir(session);
        validateAllUploadedParts(session);
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

    public void validateUploadedFileChecksum(MaterialUploadSessionEntity session, Path finalPath) throws IOException {
        String expectedChecksum = normalizeOptionalText(session.getChecksumSha256());
        if (expectedChecksum == null) {
            return;
        }
        String actualChecksum = sha256(finalPath);
        if (!expectedChecksum.equalsIgnoreCase(actualChecksum)) {
            throw new BusinessException(400, "file checksum mismatch");
        }
    }

    public void cleanupPartDir(MaterialUploadSessionEntity session) {
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

    private boolean isOrphanedUploadSession(MaterialUploadSessionEntity session) {
        if (session.getMaterialId() == null || session.getOwnerId() == null) {
            return true;
        }
        return learningMaterialRepository.findByIdAndOwnerId(session.getMaterialId(), session.getOwnerId()).isEmpty();
    }

    private void refreshSessionProgress(MaterialUploadSessionEntity session) {
        session.setUploadedChunks(uploadedPartIndexes(session).size());
        materialUploadSessionRepository.save(session);
    }

    private boolean hasAllUploadedParts(MaterialUploadSessionEntity session) {
        try {
            validateAllUploadedParts(session);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

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

    private MaterialUploadSessionEntity requireUploadSession(long ownerId, String sessionId) {
        return materialUploadSessionRepository.findById(sessionId)
            .filter(session -> session.getOwnerId() != null && session.getOwnerId() == ownerId)
            .orElseThrow(() -> new BusinessException(404, "Upload session not found"));
    }

    private void discardUploadSession(MaterialUploadSessionEntity session) {
        String storagePath = session.getStoragePath();
        cleanupPartDir(session);
        materialUploadSessionRepository.delete(session);
        materialUploadSessionRepository.flush();
        if (session.getMaterialId() == null) {
            materialDeletionService.cleanupStoredMaterialFilesAfterCommit(storagePath);
            return;
        }
        learningMaterialRepository.findByIdAndOwnerId(session.getMaterialId(), session.getOwnerId())
            .ifPresentOrElse(material -> {
                materialProcessingJobService.cancelMaterialJobs(material.getId(), "上传会话已丢弃，后台任务已取消");
                String materialStoragePath = material.getStoragePath() == null ? storagePath : material.getStoragePath();
                materialSummaryRepository.deleteByMaterialIdAndUserId(material.getId(), material.getOwnerId());
                ragQuestionSourceRepository.deleteByMaterialId(material.getId());
                vectorStoreClient.deleteMaterial(material.getOwnerId(), material.getId());
                materialPageRepository.deleteByMaterialId(material.getId());
                materialPageTextBlockRepository.deleteByMaterialId(material.getId());
                materialChunkRepository.deleteByMaterialId(material.getId());
                learningMaterialRepository.delete(material);
                materialDeletionService.cleanupDeletedMaterialAfterCommit(material.getOwnerId(), material.getId(), materialStoragePath);
            }, () -> {
                materialDeletionService.cleanupStoredMaterialFilesAfterCommit(storagePath);
            });
    }

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

    private boolean isDiscardableUploadSession(MaterialUploadSessionEntity session) {
        return session.getStatus() == MaterialUploadSessionStatus.UPLOADING
            || session.getStatus() == MaterialUploadSessionStatus.FAILED;
    }

    private MaterialUploadSessionResponse toUploadSessionResponse(MaterialUploadSessionEntity session) {
        int uploadedChunks = session.getUploadedChunks() == null ? uploadedPartIndexes(session).size() : session.getUploadedChunks();
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
            material == null ? null : material.getPageCount(),
            session.getCreatedAt() == null ? null : session.getCreatedAt().format(DATETIME_FORMATTER),
            session.getUpdatedAt() == null ? null : session.getUpdatedAt().format(DATETIME_FORMATTER)
        );
    }

    private Path resolveStoragePath(long ownerId, String originalName) {
        String safeName = originalName.replaceAll("[\\\\/:*?\"<>|]", "_");
        return storageRoot.resolve(String.valueOf(ownerId)).resolve(UUID.randomUUID() + "_" + safeName);
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
        if (fileSize > maxMaterialBytes) {
            throw new BusinessException(400, "file is too large");
        }
    }

    private MaterialSourceType parseSourceType(String sourceTypeValue, String originalName) {
        if (sourceTypeValue != null && !sourceTypeValue.isBlank()) {
            try {
                return MaterialSourceType.valueOf(sourceTypeValue.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new BusinessException(400, "Unsupported material source type");
            }
        }
        String lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (lowerName.endsWith(".md")) {
            return MaterialSourceType.MD;
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

    private String normalizeClientUploadId(String value) {
        String normalized = normalizeOptionalText(value);
        if (normalized == null) {
            throw new BusinessException(400, "clientUploadId is required");
        }
        if (normalized.length() > CLIENT_UPLOAD_ID_MAX_LENGTH) {
            return normalized.substring(0, CLIENT_UPLOAD_ID_MAX_LENGTH);
        }
        return normalized;
    }

    private String normalizeText(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? fallback : normalized;
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
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

    private boolean sameFileHash(Path first, Path second) throws IOException {
        return sha256(first).equalsIgnoreCase(sha256(second));
    }

    private void moveUploadedPart(Path tempPartPath, Path partPath) throws IOException {
        try {
            Files.move(tempPartPath, partPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(tempPartPath, partPath);
        }
    }

    public record UploadChunkResult(
        MaterialUploadSessionResponse response,
        String sessionId,
        boolean shouldScheduleProcessing
    ) {
    }
}
