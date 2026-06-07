package com.mytext.learningassistant.rag;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 按问答 ID 删除来源记录。
     *
     * @param questionId 问答记录 ID
     */
    void deleteByQuestionId(Long questionId);

    /**
     * 按资料 ID 删除来源记录（用于删除资料时的级联清理）。
     *
     * @param materialId 资料 ID
     */
    void deleteByMaterialId(Long materialId);

    /**
     * 批量删除多个问答 ID 对应的来源记录。
     *
     * @param questionIds 问答 ID 列表
     */
    @Modifying
    @Query("delete from RagQuestionSourceEntity source where source.questionId in :questionIds")
    void deleteByQuestionIdIn(@Param("questionIds") List<Long> questionIds);
}
