package com.mytext.learningassistant.rag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 用户收藏数据访问接口。
 * <p>
 * 继承 {@link JpaRepository}，提供对 {@link UserFavoriteEntity} 的 CRUD 操作，
 * 以及自定义的查询和删除方法。
 */
public interface UserFavoriteRepository extends JpaRepository<UserFavoriteEntity, Long> {

    /**
     * 查询指定用户的所有收藏记录，按创建时间倒序排列（最新的在前）。
     *
     * @param userId 用户 ID
     * @return 收藏记录列表
     */
    List<UserFavoriteEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 根据用户 ID 和问答 ID 查找收藏记录。
     * <p>
     * 用于判断某条问答是否已被当前用户收藏。
     *
     * @param userId     用户 ID
     * @param questionId 问答记录 ID
     * @return 收藏记录（可能为空）
     */
    Optional<UserFavoriteEntity> findByUserIdAndQuestionId(Long userId, Long questionId);

    /**
     * 根据收藏记录 ID 和用户 ID 查找收藏。
     * <p>
     * 用于确保用户只能操作自己的收藏。
     *
     * @param id     收藏记录 ID
     * @param userId 用户 ID
     * @return 收藏记录（可能为空）
     */
    Optional<UserFavoriteEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 判断指定问答是否已被指定用户收藏。
     *
     * @param userId     用户 ID
     * @param questionId 问答记录 ID
     * @return 已收藏返回 true，否则返回 false
     */
    boolean existsByUserIdAndQuestionId(Long userId, Long questionId);

    /**
     * 根据用户 ID 和问答 ID 删除收藏记录。
     *
     * @param userId     用户 ID
     * @param questionId 问答记录 ID
     */
    void deleteByUserIdAndQuestionId(Long userId, Long questionId);

    /**
     * 批量删除指定用户的多个问答收藏记录。
     * <p>
     * 使用自定义 JPQL 实现批量删除，提高效率。
     * 需要在事务中调用（配合 {@code @Transactional}）。
     *
     * @param userId      用户 ID
     * @param questionIds 需要取消收藏的问答 ID 列表
     */
    @Modifying
    @Query("delete from UserFavoriteEntity favorite where favorite.userId = :userId and favorite.questionId in :questionIds")
    void deleteByUserIdAndQuestionIdIn(@Param("userId") Long userId, @Param("questionIds") List<Long> questionIds);
}
