package com.mytext.learningassistant.llm;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserLlmConfigRepository extends JpaRepository<UserLlmConfigEntity, Long> {

    Optional<UserLlmConfigEntity> findByUserId(Long userId);

    List<UserLlmConfigEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<UserLlmConfigEntity> findByIdAndUserId(Long id, Long userId);

    Optional<UserLlmConfigEntity> findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(Long userId);
}
