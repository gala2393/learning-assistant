package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "rag_evaluation_suite_run")
public class RagEvaluationSuiteRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "suite_id", nullable = false)
    private Long suiteId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "total_cases", nullable = false)
    private Integer totalCases;

    @Column(name = "passed_cases", nullable = false)
    private Integer passedCases;

    @Column(name = "pass_rate", nullable = false)
    private Double passRate;

    @Column(name = "average_faithfulness_score", nullable = false)
    private Double averageFaithfulnessScore;

    @Column(name = "average_context_relevance_score", nullable = false)
    private Double averageContextRelevanceScore;

    @Column(name = "average_overall_score", nullable = false)
    private Double averageOverallScore;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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
