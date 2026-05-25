package com.mytext.learningassistant.rag;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RagQuestionSourceRepository extends JpaRepository<RagQuestionSourceEntity, Long> {

    List<RagQuestionSourceEntity> findByQuestionIdOrderByRankScoreDesc(Long questionId);

    void deleteByQuestionId(Long questionId);

    void deleteByMaterialId(Long materialId);

    @Modifying
    @Query("delete from RagQuestionSourceEntity source where source.questionId in :questionIds")
    void deleteByQuestionIdIn(@Param("questionIds") List<Long> questionIds);
}
