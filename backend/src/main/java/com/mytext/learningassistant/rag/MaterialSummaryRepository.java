package com.mytext.learningassistant.rag;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialSummaryRepository extends JpaRepository<MaterialSummaryEntity, Long> {

    Optional<MaterialSummaryEntity> findFirstByMaterialIdAndUserIdOrderByCreatedAtDesc(long materialId, long userId);

    List<MaterialSummaryEntity> findByMaterialIdAndUserIdOrderByCreatedAtDesc(long materialId, long userId);

    void deleteByMaterialIdAndUserId(long materialId, long userId);
}
