package com.mytext.learningassistant.rag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * RAG 评估数据访问接口 —— 提供对 {@link RagEvaluationEntity} 的数据库操作。
 *
 * <p>支持按问答 ID 和用户 ID 查询评估结果、按问答 ID 删除评估记录等操作。</p>
 */
public interface RagEvaluationRepository extends JpaRepository<RagEvaluationEntity, Long> {

    /**
     * 查询指定问答记录和用户的评估结果。
     *
     * @param questionId 问答记录 ID
     * @param userId     用户 ID
     * @return 评估结果的 Optional 包装
     */
    Optional<RagEvaluationEntity> findByQuestionIdAndUserId(Long questionId, Long userId);

    /**
     * 按问答 ID 删除评估记录。
     *
     * @param questionId 问答记录 ID
     */
    void deleteByQuestionId(Long questionId);

    /**
     * 批量删除多个问答 ID 对应的评估记录（用于清除历史时的级联删除）。
     *
     * @param questionIds 问答 ID 列表
     */
    @Modifying
    @Query("delete from RagEvaluationEntity evaluation where evaluation.questionId in :questionIds")
    void deleteByQuestionIdIn(@Param("questionIds") List<Long> questionIds);
}
