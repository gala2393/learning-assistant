package com.mytext.learningassistant.rag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RagEvaluationSuiteRunRepository extends JpaRepository<RagEvaluationSuiteRunEntity, Long> {

    List<RagEvaluationSuiteRunEntity> findBySuiteIdAndUserIdOrderByCreatedAtDesc(Long suiteId, Long userId);

    Optional<RagEvaluationSuiteRunEntity> findFirstBySuiteIdAndUserIdOrderByCreatedAtDesc(Long suiteId, Long userId);

    void deleteBySuiteId(Long suiteId);
}
