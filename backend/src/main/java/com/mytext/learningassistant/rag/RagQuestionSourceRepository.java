package com.mytext.learningassistant.rag;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * RAG 问答来源数据访问接口 —— 提供对 {@link RagQuestionSourceEntity} 的数据库操作。
 */
public interface RagQuestionSourceRepository extends JpaRepository<RagQuestionSourceEntity, Long> {

    /**
     * 查询指定问答记录的所有来源（按相关性评分降序排列）。
     *
     * @param questionId 问答记录 ID
     * @return 来源列表
     */
    List<RagQuestionSourceEntity> findByQuestionIdOrderByRankScoreDesc(Long questionId);

    /**
     * 查询某份资料关联过的问答 ID，按来源记录创建时间倒序。
     * 用于兼容 material_id 字段上线前的旧问答历史。
     *
     * @param materialId 资料 ID
     * @return 问答 ID 列表
     */
    @Query("select source.questionId from RagQuestionSourceEntity source where source.materialId = :materialId order by source.createdAt desc")
    List<Long> findQuestionIdsByMaterialIdOrderByCreatedAtDesc(@Param("materialId") Long materialId);

    /**
     * 按问答 ID 删除来源记录。
     *
     * @param questionId 问答记录 ID
     */
    @Transactional
    void deleteByQuestionId(Long questionId);

    /**
     * 按资料 ID 删除来源记录（用于删除资料时的级联清理）。
     *
     * @param materialId 资料 ID
     */
    /** 批量删除资料关联来源，避免删除资料时逐条加载问答来源实体。 */
    @Modifying
    @Query("delete from RagQuestionSourceEntity source where source.materialId = :materialId")
    @Transactional
    void deleteByMaterialId(@Param("materialId") Long materialId);

    /**
     * 批量删除多个问答 ID 对应的来源记录。
     *
     * @param questionIds 问答 ID 列表
     */
    @Modifying
    @Query("delete from RagQuestionSourceEntity source where source.questionId in :questionIds")
    @Transactional
    void deleteByQuestionIdIn(@Param("questionIds") List<Long> questionIds);
}
