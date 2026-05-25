package com.mytext.learningassistant.material;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialUploadSessionRepository extends JpaRepository<MaterialUploadSessionEntity, String> {

    Optional<MaterialUploadSessionEntity> findByOwnerIdAndClientUploadId(Long ownerId, String clientUploadId);
}
