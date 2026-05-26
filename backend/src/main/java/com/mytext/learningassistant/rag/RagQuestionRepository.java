package com.mytext.learningassistant.rag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RagQuestionRepository extends JpaRepository<RagQuestionEntity, Long> {

    List<RagQuestionEntity> findByUserIdOrderByPinnedDescCreatedAtDesc(Long userId);

    List<RagQuestionEntity> findByUserIdAndConversationIdOrderByCreatedAtAsc(Long userId, Long conversationId);

    Optional<RagQuestionEntity> findByIdAndUserId(Long id, Long userId);

    void deleteByUserId(Long userId);
}
