package com.mytext.learningassistant.vector;

import java.util.List;
import java.util.Map;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

public interface VectorStoreClient {

    boolean configured();

    void upsertChunks(long userId, LearningMaterialEntity material, List<MaterialChunkEntity> chunks, Map<Long, List<Double>> embeddingsByChunkId);

    void deleteMaterial(long userId, long materialId);

    List<VectorSearchResult> search(long userId, Long materialId, List<Double> queryEmbedding, int limit, double scoreThreshold);
}
