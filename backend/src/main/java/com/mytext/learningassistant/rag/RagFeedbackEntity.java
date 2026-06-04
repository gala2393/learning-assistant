package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 用户反馈实体 —— 对应数据库中的 {@code rag_feedback} 表。
 *
 * <p>存储用户对 RAG 问答结果的评价反馈，包括评分（好评/差评）和文字评论。
 * 每个用户对每条问答只能提交一次反馈，重复提交会覆盖之前的反馈。</p>
 *
 * <p>反馈数据可用于：</p>
 * <ul>
 *   <li>分析 RAG 系统的回答质量</li>
 *   <li>为后续的模型微调或 prompt 优化提供依据</li>
 *   <li>识别常见问题和薄弱环节</li>
 * </ul>
 */
@Entity
@Table(
    name = "rag_feedback",
    uniqueConstraints = @UniqueConstraint(name = "uk_rag_feedback_user_question", columnNames = {"user_id", "question_id"})
)
public class RagFeedbackEntity {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 问答记录 ID */
    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /** 评分：1 表示好评，-1 表示差评 */
    @Column(name = "rating", nullable = false)
    private Integer rating;

    /** 文字评论（可选） */
    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA 生命周期回调：在实体首次持久化前设置创建和更新时间。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /**
     * JPA 生命周期回调：在实体更新前刷新更新时间。
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
