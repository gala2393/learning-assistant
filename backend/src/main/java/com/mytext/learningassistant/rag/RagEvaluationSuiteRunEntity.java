package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * RAG 评估套件运行记录实体 —— 对应数据库中的 {@code rag_evaluation_suite_run} 表。
 *
 * <p>每次运行评估套件（无论是手动触发还是定时执行）都会生成一条运行记录，
 * 记录当次运行的整体指标和详细结果（以 JSON 格式存储）。这些历史记录
 * 可用于追踪 RAG 系统质量的变化趋势。</p>
 */
@Entity
@Table(name = "rag_evaluation_suite_run")
public class RagEvaluationSuiteRunEntity {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属评估套件 ID */
    @Column(name = "suite_id", nullable = false)
    private Long suiteId;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 总用例数 */
    @Column(name = "total_cases", nullable = false)
    private Integer totalCases;

    /** 通过的用例数 */
    @Column(name = "passed_cases", nullable = false)
    private Integer passedCases;

    /** 通过率（0~1） */
    @Column(name = "pass_rate", nullable = false)
    private Double passRate;

    /** 平均忠实度得分 */
    @Column(name = "average_faithfulness_score", nullable = false)
    private Double averageFaithfulnessScore;

    /** 平均上下文相关性得分 */
    @Column(name = "average_context_relevance_score", nullable = false)
    private Double averageContextRelevanceScore;

    /** 平均综合得分 */
    @Column(name = "average_overall_score", nullable = false)
    private Double averageOverallScore;

    /** 详细结果 JSON（包含各用例的评估数据） */
    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * JPA 生命周期回调：在实体首次持久化前设置创建时间。
     */
    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getSuiteId() {
        return suiteId;
    }

    public void setSuiteId(Long suiteId) {
        this.suiteId = suiteId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getTotalCases() {
        return totalCases;
    }

    public void setTotalCases(Integer totalCases) {
        this.totalCases = totalCases;
    }

    public Integer getPassedCases() {
        return passedCases;
    }

    public void setPassedCases(Integer passedCases) {
        this.passedCases = passedCases;
    }

    public Double getPassRate() {
        return passRate;
    }

    public void setPassRate(Double passRate) {
        this.passRate = passRate;
    }

    public Double getAverageFaithfulnessScore() {
        return averageFaithfulnessScore;
    }

    public void setAverageFaithfulnessScore(Double averageFaithfulnessScore) {
        this.averageFaithfulnessScore = averageFaithfulnessScore;
    }

    public Double getAverageContextRelevanceScore() {
        return averageContextRelevanceScore;
    }

    public void setAverageContextRelevanceScore(Double averageContextRelevanceScore) {
        this.averageContextRelevanceScore = averageContextRelevanceScore;
    }

    public Double getAverageOverallScore() {
        return averageOverallScore;
    }

    public void setAverageOverallScore(Double averageOverallScore) {
        this.averageOverallScore = averageOverallScore;
    }

    public String getResultJson() {
        return resultJson;
    }

    public void setResultJson(String resultJson) {
        this.resultJson = resultJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
