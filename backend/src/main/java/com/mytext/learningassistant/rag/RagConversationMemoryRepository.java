package com.mytext.learningassistant.rag;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 会话长期记忆仓库。
 *
 * <p>按 userId + conversationId 定位记忆，保证不同用户之间的会话摘要互相隔离。</p>
 */
public interface RagConversationMemoryRepository extends JpaRepository<RagConversationMemoryEntity, Long> {

    Optional<RagConversationMemoryEntity> findByUserIdAndConversationId(Long userId, Long conversationId);

    void deleteByUserId(Long userId);
}
