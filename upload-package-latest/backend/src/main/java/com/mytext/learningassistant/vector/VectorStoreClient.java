package com.mytext.learningassistant.vector;

import java.util.List;
import java.util.Map;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

/**
 * 向量存储客户端接口（Vector Store Client）。
 *
 * <p>职责：定义对向量数据库的统一操作抽象，包括写入（upsert）、删除和搜索。
 * 具体实现可以对接不同的向量数据库（如 Qdrant、Milvus、Weaviate 等）。</p>
 *
 * <p>典型使用场景：</p>
 * <ul>
 *   <li>学习资料上传后，将文本块的嵌入向量写入数据库</li>
 *   <li>用户提问时，基于问题的嵌入向量进行相似度搜索</li>
 *   <li>学习资料删除时，清理对应的向量数据</li>
 * </ul>
 */
public interface VectorStoreClient {

    /**
     * 检查向量存储服务是否已正确配置。
     *
     * @return 如果配置有效返回 true；否则返回 false（此时其他方法通常会静默跳过）
     */
    boolean configured();

    /**
     * 将学习资料的文本块及对应嵌入向量写入（upsert）向量数据库。
     *
     * @param userId             所属用户 ID
     * @param material           学习资料实体
     * @param chunks             资料的文本块列表
     * @param embeddingsByChunkId 文本块 ID 到嵌入向量的映射
     */
    void upsertChunks(long userId, LearningMaterialEntity material, List<MaterialChunkEntity> chunks, Map<Long, List<Double>> embeddingsByChunkId);

    /**
     * 删除指定用户下某个学习资料的所有向量数据。
     *
     * @param userId     用户 ID
     * @param materialId 学习资料 ID
     */
    void deleteMaterial(long userId, long materialId);

    /**
     * 基于向量相似度搜索最相关的文本块。
     *
     * @param userId         用户 ID，用于限定搜索范围
     * @param materialId     可选的资料 ID；为 null 时搜索该用户下所有资料
     * @param queryEmbedding 查询文本的嵌入向量
     * @param limit          最大返回结果数
     * @param scoreThreshold 最低相似度阈值，低于此值的结果会被过滤
     * @return 匹配结果列表（按相似度降序）；无结果时返回空列表
     */
    List<VectorSearchResult> search(long userId, Long materialId, List<Double> queryEmbedding, int limit, double scoreThreshold);
}
