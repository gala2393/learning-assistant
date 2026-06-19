package com.mytext.learningassistant.material;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.embedding.EmbeddingClient;
import com.mytext.learningassistant.vector.VectorStoreClient;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 资料向量索引服务。
 *
 * <p>负责资料切片的 Embedding 生成、向量库批量写入和后台重建索引。
 * 解析服务只需要生成切片并调用这里，避免上传/解析主流程继续承载向量数据库细节。</p>
 */
@Service
public class MaterialIndexingService {

    private static final Logger log = LoggerFactory.getLogger(MaterialIndexingService.class);

    /** Embedding 向量缓存的最大条目数。 */
    private static final int EMBEDDING_CACHE_MAX_ENTRIES = 2_048;

    /** 写入向量库时的批大小，避免超大资料一次性持有全部切片和向量。 */
    private static final int VECTOR_UPSERT_BATCH_SIZE = 200;

    private final LearningMaterialRepository learningMaterialRepository;
    private final MaterialChunkRepository materialChunkRepository;
    private final ObjectMapper objectMapper;
    private final EmbeddingClient embeddingClient;
    private final VectorStoreClient vectorStoreClient;
    private final ExecutorService vectorIndexExecutor = Executors.newFixedThreadPool(2);
    private final ConcurrentMap<String, String> embeddingJsonCache = new ConcurrentHashMap<>();

    public MaterialIndexingService(
        LearningMaterialRepository learningMaterialRepository,
        MaterialChunkRepository materialChunkRepository,
        ObjectMapper objectMapper,
        EmbeddingClient embeddingClient,
        VectorStoreClient vectorStoreClient
    ) {
        this.learningMaterialRepository = learningMaterialRepository;
        this.materialChunkRepository = materialChunkRepository;
        this.objectMapper = objectMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStoreClient = vectorStoreClient;
    }

    @PreDestroy
    void shutdown() {
        vectorIndexExecutor.shutdownNow();
    }

    /**
     * 为文本生成 Embedding 向量的 JSON 字符串。
     *
     * <p>清理图片标记后按文本哈希做内存缓存，同一片段重复解析时避免再次请求外部 Embedding 服务。</p>
     */
    public String toEmbeddingJson(String text) {
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
     * 将 Embedding JSON 解析为向量列表，解析失败时返回 null。
     */
    public List<Double> parseEmbeddingJson(String embeddingJson) {
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
     * 判断当前向量批次是否应立即写入向量库。
     */
    public boolean shouldFlushVectorBatch(int batchSize) {
        return batchSize >= VECTOR_UPSERT_BATCH_SIZE;
    }

    /**
     * 将累积的知识片段和向量批量写入向量数据库，写入后清空待处理集合。
     */
    public void flushVectorBatch(
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
     * 在事务提交后异步重建资料向量索引。
     *
     * <p>如果当前没有 Spring 事务，直接提交后台任务；如果有事务，等提交成功后再跑，
     * 防止后台线程读到尚未提交的切片数据。</p>
     */
    public void scheduleVectorIndexRebuildAfterCommit(long ownerId, Long materialId) {
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
                String embeddingJson = toEmbeddingJson(embeddingText(chunk));
                if (embeddingJson != null && !Objects.equals(embeddingJson, chunk.getEmbeddingJson())) {
                    if (!materialStillAvailableForVectorIndex(ownerId, materialId)) {
                        log.info("Skip background vector index rebuild because material was removed: materialId={}", materialId);
                        return;
                    }
                    chunk.setEmbeddingJson(embeddingJson);
                    try {
                        materialChunkRepository.save(chunk);
                    } catch (ObjectOptimisticLockingFailureException exception) {
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

    private String embeddingText(MaterialChunkEntity chunk) {
        if (chunk == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        appendEmbeddingPart(builder, chunk.getSectionTitle());
        appendEmbeddingPart(builder, chunk.getHierarchyPath());
        appendEmbeddingPart(builder, chunk.getKeywords());
        appendEmbeddingPart(builder, chunk.getSummary());
        appendEmbeddingPart(builder, chunk.getChunkText());
        return builder.toString();
    }

    private void appendEmbeddingPart(StringBuilder builder, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append('\n');
        }
        builder.append(value.trim());
    }

    private String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** 向量索引重建任务的最小定位信息，避免把 JPA 实体直接传入后台线程。 */
    private record VectorIndexRebuildTarget(long ownerId, Long materialId) {
    }
}
