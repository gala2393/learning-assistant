package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RagEvaluationSuiteRepository extends JpaRepository<RagEvaluationSuiteEntity, Long> {

    List<RagEvaluationSuiteEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<RagEvaluationSuiteEntity> findByIdAndUserId(Long id, Long userId);

    List<RagEvaluationSuiteEntity> findByScheduledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(LocalDateTime now);
}
