package com.mytext.learningassistant.rag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RagFeedbackRepository extends JpaRepository<RagFeedbackEntity, Long> {

    Optional<RagFeedbackEntity> findByQuestionIdAndUserId(Long questionId, Long userId);

    void deleteByQuestionId(Long questionId);

    @Modifying
    @Query("delete from RagFeedbackEntity feedback where feedback.questionId in :questionIds")
    void deleteByQuestionIdIn(@Param("questionIds") List<Long> questionIds);
}
