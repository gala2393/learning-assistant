package com.mytext.learningassistant.rag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserFavoriteRepository extends JpaRepository<UserFavoriteEntity, Long> {

    List<UserFavoriteEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<UserFavoriteEntity> findByUserIdAndQuestionId(Long userId, Long questionId);

    Optional<UserFavoriteEntity> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndQuestionId(Long userId, Long questionId);

    void deleteByUserIdAndQuestionId(Long userId, Long questionId);

    @Modifying
    @Query("delete from UserFavoriteEntity favorite where favorite.userId = :userId and favorite.questionId in :questionIds")
    void deleteByUserIdAndQuestionIdIn(@Param("userId") Long userId, @Param("questionIds") List<Long> questionIds);
}
