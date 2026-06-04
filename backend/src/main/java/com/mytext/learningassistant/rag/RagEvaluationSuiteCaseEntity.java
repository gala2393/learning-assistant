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
 * RAG 评估套件用例实体 —— 对应数据库中的 {@code rag_evaluation_suite_case} 表。
 *
 * <p>每个评估套件（{@link RagEvaluationSuiteEntity}）包含多个测试用例，
 * 每个用例定义了一个待测试的问题、可选的资料范围，以及期望的关键词。
 * 运行评估套件时，系统会对每个用例执行完整的 RAG 问答流程并自动打分。</p>
 */
@Entity
@Table(name = "rag_evaluation_suite_case")
public class RagEvaluationSuiteCaseEntity {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属评估套件的 ID */
    @Column(name = "suite_id", nullable = false)
    private Long suiteId;

    /** 用例在套件中的序号（从 1 开始） */
    @Column(name = "case_index", nullable = false)
    private Integer caseIndex;

    /** 待测试的问题文本 */
    @Column(name = "question", nullable = false, length = 2000)
    private String question;

    /** 限定检索范围的资料 ID（可选） */
    @Column(name = "material_id")
    private Long materialId;

    /** 期望回答中应包含的关键词（JSON 数组格式存储） */
    @Column(name = "expected_answer_terms", columnDefinition = "TEXT")
    private String expectedAnswerTerms;

    /** 期望引用来源中应包含的关键词（JSON 数组格式存储） */
    @Column(name = "expected_source_terms", columnDefinition = "TEXT")
    private String expectedSourceTerms;

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

    public Long getSuiteId() {
        return suiteId;
    }

    public void setSuiteId(Long suiteId) {
        this.suiteId = suiteId;
    }

    public Integer getCaseIndex() {
        return caseIndex;
    }

    public void setCaseIndex(Integer caseIndex) {
        this.caseIndex = caseIndex;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public String getExpectedAnswerTerms() {
        return expectedAnswerTerms;
    }

    public void setExpectedAnswerTerms(String expectedAnswerTerms) {
        this.expectedAnswerTerms = expectedAnswerTerms;
    }

    public String getExpectedSourceTerms() {
        return expectedSourceTerms;
    }

    public void setExpectedSourceTerms(String expectedSourceTerms) {
        this.expectedSourceTerms = expectedSourceTerms;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
