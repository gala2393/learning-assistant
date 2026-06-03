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
@Table(name = "rag_evaluation_suite_case")
public class RagEvaluationSuiteCaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "suite_id", nullable = false)
    private Long suiteId;

    @Column(name = "case_index", nullable = false)
    private Integer caseIndex;

    @Column(name = "question", nullable = false, length = 2000)
    private String question;

    @Column(name = "material_id")
    private Long materialId;

    @Column(name = "expected_answer_terms", columnDefinition = "TEXT")
    private String expectedAnswerTerms;

    @Column(name = "expected_source_terms", columnDefinition = "TEXT")
    private String expectedSourceTerms;

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
