package com.mytext.learningassistant.rag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 用户反馈数据访问接口 —— 提供对 {@link RagFeedbackEntity} 的数据库操作。
 */
public interface RagFeedbackRepository extends JpaRepository<RagFeedbackEntity, Long> {

    /**
     * 查询指定问答记录和用户的反馈。
     *
     * @param questionId 问答记录 ID
     * @param userId     用户 ID
     * @return 反馈的 Optional 包装
     */
    Optional<RagFeedbackEntity> findByQuestionIdAndUserId(Long questionId, Long userId);

    /**
     * 按问答 ID 删除反馈记录。
     *
     * @param questionId 问答记录 ID
     */
    void deleteByQuestionId(Long questionId);

    /**
     * 批量删除多个问答 ID 对应的反馈记录。
     *
     * @param questionIds 问答 ID 列表
     */
    @Modifying
    @Query("delete from RagFeedbackEntity feedback where feedback.questionId in :questionIds")
    void deleteByQuestionIdIn(@Param("questionIds") List<Long> questionIds);
}
