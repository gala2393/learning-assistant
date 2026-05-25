package com.mytext.learningassistant.material;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningMaterialRepository extends JpaRepository<LearningMaterialEntity, Long> {

    List<LearningMaterialEntity> findAllByOrderByCreatedAtDesc();

    List<LearningMaterialEntity> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Optional<LearningMaterialEntity> findByIdAndOwnerId(Long id, Long ownerId);
}
