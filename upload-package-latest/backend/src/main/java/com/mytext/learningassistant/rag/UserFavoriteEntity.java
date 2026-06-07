package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 用户收藏实体类。
 * <p>
 * 对应数据库表 {@code user_favorite}，记录用户收藏的问答记录。
 * 每个用户对每条问答最多只能收藏一次（通过联合唯一约束保证）。
 * <p>
 * 收藏功能允许用户将有价值的问答记录标记为常用，
 * 方便后续在"收藏"页面快速查看。
 */
@Entity
@Table(
    name = "user_favorite",
    uniqueConstraints = @UniqueConstraint(name = "uk_user_favorite_user_question", columnNames = {"user_id", "question_id"})
)
public class UserFavoriteEntity {

    /** 收藏记录的主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 收藏所属的用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 被收藏的问答记录 ID */
    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /** 收藏的创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * 获取收藏记录 ID。
     *
     * @return 收藏记录 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 设置收藏记录 ID。
     *
     * @param id 收藏记录 ID
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * 获取用户 ID。
     *
     * @return 用户 ID
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 设置用户 ID。
     *
     * @param userId 用户 ID
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /**
     * 获取被收藏的问答记录 ID。
     *
     * @return 问答记录 ID
     */
    public Long getQuestionId() {
        return questionId;
    }

    /**
     * 设置被收藏的问答记录 ID。
     *
     * @param questionId 问答记录 ID
     */
    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    /**
     * 获取收藏创建时间。
     *
     * @return 创建时间
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 设置收藏创建时间。
     *
     * @param createdAt 创建时间
     */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
