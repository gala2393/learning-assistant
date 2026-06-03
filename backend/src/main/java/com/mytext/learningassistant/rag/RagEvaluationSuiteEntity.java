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

@Entity
@Table(name = "rag_evaluation_suite")
public class RagEvaluationSuiteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "last_total_cases")
    private Integer lastTotalCases;

    @Column(name = "last_passed_cases")
    private Integer lastPassedCases;

    @Column(name = "last_pass_rate")
    private Double lastPassRate;

    @Column(name = "last_average_overall_score")
    private Double lastAverageOverallScore;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    @Column(name = "scheduled", nullable = false)
    private Boolean scheduled = false;

    @Column(name = "schedule_interval_hours", nullable = false)
    private Integer scheduleIntervalHours = 24;

    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getLastTotalCases() {
        return lastTotalCases;
    }

    public void setLastTotalCases(Integer lastTotalCases) {
        this.lastTotalCases = lastTotalCases;
    }

    public Integer getLastPassedCases() {
        return lastPassedCases;
    }

    public void setLastPassedCases(Integer lastPassedCases) {
        this.lastPassedCases = lastPassedCases;
    }

    public Double getLastPassRate() {
        return lastPassRate;
    }

    public void setLastPassRate(Double lastPassRate) {
        this.lastPassRate = lastPassRate;
    }

    public Double getLastAverageOverallScore() {
        return lastAverageOverallScore;
    }

    public void setLastAverageOverallScore(Double lastAverageOverallScore) {
        this.lastAverageOverallScore = lastAverageOverallScore;
    }

    public LocalDateTime getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(LocalDateTime lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public Boolean getScheduled() {
        return scheduled;
    }

    public void setScheduled(Boolean scheduled) {
        this.scheduled = scheduled;
    }

    public Integer getScheduleIntervalHours() {
        return scheduleIntervalHours;
    }

    public void setScheduleIntervalHours(Integer scheduleIntervalHours) {
        this.scheduleIntervalHours = scheduleIntervalHours;
    }

    public LocalDateTime getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(LocalDateTime nextRunAt) {
        this.nextRunAt = nextRunAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
