package com.mytext.learningassistant.rag;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RagEvaluationSuiteCaseRepository extends JpaRepository<RagEvaluationSuiteCaseEntity, Long> {

    List<RagEvaluationSuiteCaseEntity> findBySuiteIdOrderByCaseIndexAsc(Long suiteId);

    void deleteBySuiteId(Long suiteId);
}
