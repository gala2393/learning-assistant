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

/**
 * RAG 评估实体 —— 对应数据库中的 {@code rag_evaluation} 表。
 *
 * <p>存储对单条 RAG 问答结果的自动评估数据。评估指标包括：</p>
 * <ul>
 *   <li><strong>忠实度（Faithfulness）</strong>：回答中的内容是否都有资料依据，是否存在"编造"</li>
 *   <li><strong>上下文相关性（Context Relevance）</strong>：检索到的资料片段是否与问题相关</li>
 *   <li><strong>综合评分（Overall Score）</strong>：综合以上两项的加权得分</li>
 * </ul>
 *
 * <p>评估方法：通过比较问题关键词、回答关键词与资料来源关键词的覆盖程度，
 * 采用基于 term overlap 的统计方法进行自动打分。</p>
 */
@Entity
@Table(name = "rag_evaluation")
public class RagEvaluationEntity {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的问答记录 ID（唯一约束：一条问答只能有一个评估） */
    @Column(name = "question_id", nullable = false, unique = true)
    private Long questionId;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 忠实度得分（0~1），衡量回答是否忠于资料来源 */
    @Column(name = "faithfulness_score", nullable = false)
    private Double faithfulnessScore;

    /** 上下文相关性得分（0~1），衡量检索到的资料是否与问题相关 */
    @Column(name = "context_relevance_score", nullable = false)
    private Double contextRelevanceScore;

    /** 综合得分（0~1），忠实度和相关性的加权平均 */
    @Column(name = "overall_score", nullable = false)
    private Double overallScore;

    /** 评估判定："PASS"（通过）、"WARN"（警告）、"FAIL"（失败） */
    @Column(name = "verdict", nullable = false, length = 32)
    private String verdict;

    /** 评估证据：记录匹配的关键词和缺失的关键词，用于分析评估结果 */
    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;

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
        createdAt = now;
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

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Double getFaithfulnessScore() {
        return faithfulnessScore;
    }

    public void setFaithfulnessScore(Double faithfulnessScore) {
        this.faithfulnessScore = faithfulnessScore;
    }

    public Double getContextRelevanceScore() {
        return contextRelevanceScore;
    }

    public void setContextRelevanceScore(Double contextRelevanceScore) {
        this.contextRelevanceScore = contextRelevanceScore;
    }

    public Double getOverallScore() {
        return overallScore;
    }

    public void setOverallScore(Double overallScore) {
        this.overallScore = overallScore;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
