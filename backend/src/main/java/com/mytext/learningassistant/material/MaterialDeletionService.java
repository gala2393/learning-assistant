package com.mytext.learningassistant.material;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.rag.MaterialSummaryRepository;
import com.mytext.learningassistant.rag.RagQuestionSourceRepository;
import com.mytext.learningassistant.vector.VectorStoreClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 资料删除服务。
 *
 * <p>集中处理资料删除时的数据库关联清理、后台任务取消、向量索引删除和磁盘文件清理。
 * MaterialService 只保留对外门面，避免删除流程继续散落在大类里。</p>
 */
@Service
class MaterialDeletionService {

    private static final Logger log = LoggerFactory.getLogger(MaterialDeletionService.class);
    private static final String ASSET_SUFFIX = ".assets";
    private static final String PREVIEW_SUFFIX = ".preview.pdf";

    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final MaterialUploadSessionRepository materialUploadSessionRepository;
    private final MaterialSummaryRepository materialSummaryRepository;
    private final RagQuestionSourceRepository ragQuestionSourceRepository;
    private final MaterialPageRepository materialPageRepository;
    private final MaterialPageTextBlockRepository materialPageTextBlockRepository;
    private final MaterialProcessingJobService materialProcessingJobService;
    private final VectorStoreClient vectorStoreClient;
    private final Path storageRoot;

    MaterialDeletionService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        MaterialUploadSessionRepository materialUploadSessionRepository,
        MaterialSummaryRepository materialSummaryRepository,
        RagQuestionSourceRepository ragQuestionSourceRepository,
        MaterialPageRepository materialPageRepository,
        MaterialPageTextBlockRepository materialPageTextBlockRepository,
        MaterialProcessingJobService materialProcessingJobService,
        VectorStoreClient vectorStoreClient,
        @Value("${app.storage-dir:${user.dir}/target/learning-assistant-files}") String storageDir
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.materialUploadSessionRepository = materialUploadSessionRepository;
        this.materialSummaryRepository = materialSummaryRepository;
        this.ragQuestionSourceRepository = ragQuestionSourceRepository;
        this.materialPageRepository = materialPageRepository;
        this.materialPageTextBlockRepository = materialPageTextBlockRepository;
        this.materialProcessingJobService = materialProcessingJobService;
        this.vectorStoreClient = vectorStoreClient;
        this.storageRoot = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @Transactional
    void delete(long ownerId, long materialId) {
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

    void cleanupStoredMaterialFilesAfterCommit(String storagePath) {
        runAfterCommit(() -> cleanupStoredMaterialFiles(storagePath));
    }

    void cleanupDeletedMaterialAfterCommit(long ownerId, long materialId, String storagePath) {
        runAfterCommit(() -> {
            try {
                vectorStoreClient.deleteMaterial(ownerId, materialId);
            } catch (Exception exception) {
                log.warn("Failed to delete material vectors after commit: materialId={}", materialId, exception);
            }
            cleanupStoredMaterialFiles(storagePath);
        });
    }

    private void cleanupStoredMaterialFiles(String storagePath) {
        deleteStoredAssets(storagePath);
        deleteStoredFile(storagePath);
        deletePreviewFile(storagePath);
    }

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

    private void deleteStoredFile(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolveStoredPath(storagePath));
        } catch (IOException ignored) {
            // 删除接口不因为磁盘残留阻塞数据库删除，后续可通过后台清理任务兜底。
        }
    }

    private void deletePreviewFile(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(previewPdfPath(resolveStoredPath(storagePath)));
        } catch (IOException ignored) {
            // 删除接口不因为预览文件残留阻塞数据库删除。
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
                    // 同上，磁盘残留不应回滚资料删除。
                }
            });
        } catch (IOException ignored) {
            // 同上，磁盘残留不应回滚资料删除。
        }
    }

    private Path assetDir(Path sourceFile) {
        return Path.of(sourceFile.toString() + ASSET_SUFFIX);
    }

    private Path previewPdfPath(Path sourceFile) {
        return Path.of(sourceFile.toString() + PREVIEW_SUFFIX).toAbsolutePath().normalize();
    }

    private Path resolveStoredPath(String storagePath) {
        Path rawPath = Path.of(storagePath);
        if (!rawPath.isAbsolute()) {
            return storageRoot.resolve(rawPath).normalize();
        }
        Path absolutePath = rawPath.toAbsolutePath().normalize();
        Optional<Path> remappedPath = remapToCurrentStorageRoot(absolutePath);
        if (remappedPath.isPresent()) {
            return remappedPath.get();
        }
        if (absolutePath.startsWith(storageRoot)) {
            return absolutePath;
        }
        throw new BusinessException(400, "Invalid stored file path");
    }

    private Optional<Path> remapToCurrentStorageRoot(Path absolutePath) {
        Path storageRootName = storageRoot.getFileName();
        if (storageRootName == null) {
            return Optional.empty();
        }
        String rootName = storageRootName.toString();
        for (int index = 0; index < absolutePath.getNameCount(); index++) {
            if (rootName.equals(absolutePath.getName(index).toString())) {
                Path relativePath = absolutePath.subpath(index + 1, absolutePath.getNameCount());
                Path remappedPath = storageRoot.resolve(relativePath).normalize();
                if (remappedPath.startsWith(storageRoot)) {
                    return Optional.of(remappedPath);
                }
            }
        }
        return Optional.empty();
    }
}
