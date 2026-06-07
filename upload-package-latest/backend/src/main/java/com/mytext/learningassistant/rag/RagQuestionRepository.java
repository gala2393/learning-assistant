package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * RAG 问答记录数据访问接口 —— 提供对 {@link RagQuestionEntity} 的数据库操作。
 *
 * <p>主要查询场景：历史列表、会话详情、每日用量统计等。</p>
 */
public interface RagQuestionRepository extends JpaRepository<RagQuestionEntity, Long> {

    /**
     * 查询指定用户的所有问答记录（置顶优先，然后按创建时间倒序）。
     *
     * @param userId 用户 ID
     * @return 问答记录列表
     */
    List<RagQuestionEntity> findByUserIdOrderByPinnedDescCreatedAtDesc(Long userId);

    /**
     * 查询指定用户在某个会话中的所有问答记录（按创建时间升序）。
     *
     * @param userId         用户 ID
     * @param conversationId 会话 ID
     * @return 问答记录列表
     */
    List<RagQuestionEntity> findByUserIdAndConversationIdOrderByCreatedAtAsc(Long userId, Long conversationId);

    /**
     * 按 ID 和用户 ID 查找问答记录（确保用户只能访问自己的记录）。
     *
     * @param id     问答 ID
     * @param userId 用户 ID
     * @return 问答记录的 Optional 包装
     */
    Optional<RagQuestionEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 统计指定用户在某时间之后的问答数量（用于每日用量限制判断）。
     *
     * @param userId      用户 ID
     * @param createdAt 起始时间
     * @return 问答数量
     */
    long countByUserIdAndCreatedAtGreaterThanEqual(Long userId, LocalDateTime createdAt);

    /**
     * 删除指定用户的所有问答记录。
     *
     * @param userId 用户 ID
     */
    void deleteByUserId(Long userId);
}
