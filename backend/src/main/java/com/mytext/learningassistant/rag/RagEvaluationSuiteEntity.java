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
 * RAG 评估套件实体 —— 对应数据库中的 {@code rag_evaluation_suite} 表。
 *
 * <p>评估套件是多个测试用例的集合，用于对 RAG 系统的整体质量进行批量评估。
 * 用户可以创建多个套件，每个套件包含不同的测试问题集。</p>
 *
 * <p>套件支持定时执行功能：配置定时后，系统会按照设定的间隔自动运行评估，
 * 记录历史运行结果，便于持续监控 RAG 系统的质量变化趋势。</p>
 */
@Entity
@Table(name = "rag_evaluation_suite")
public class RagEvaluationSuiteEntity {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 套件名称 */
    @Column(name = "name", nullable = false, length = 120)
    private String name;

    /** 套件描述 */
    @Column(name = "description", length = 1000)
    private String description;

    /** 最近一次运行的总用例数 */
    @Column(name = "last_total_cases")
    private Integer lastTotalCases;

    /** 最近一次运行的通过用例数 */
    @Column(name = "last_passed_cases")
    private Integer lastPassedCases;

    /** 最近一次运行的通过率 */
    @Column(name = "last_pass_rate")
    private Double lastPassRate;

    /** 最近一次运行的平均综合得分 */
    @Column(name = "last_average_overall_score")
    private Double lastAverageOverallScore;

    /** 最近一次运行的时间 */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 是否启用定时执行 */
    @Column(name = "scheduled", nullable = false)
    private Boolean scheduled = false;

    /** 定时执行间隔（小时），默认 24 小时 */
    @Column(name = "schedule_interval_hours", nullable = false)
    private Integer scheduleIntervalHours = 24;

    /** 下次计划执行时间 */
    @Column(name = "next_run_at")
    private LocalDateTime nextRunAt;

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
