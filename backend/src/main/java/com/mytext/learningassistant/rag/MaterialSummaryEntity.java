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
 * 学习资料摘要实体 —— 对应数据库中的 {@code material_summary} 表。
 *
 * <p>当用户对一份学习资料执行"生成摘要"操作时，系统会调用 LLM 对资料内容进行总结，
 * 并将摘要结果持久化到此表中。每份资料可以有多个历史摘要记录（按时间倒序排列）。</p>
 *
 * <p>摘要在 RAG 流程中的作用：当用户的查询过于宽泛（如"这本书讲了什么"）时，
 * 系统可以利用摘要来辅助定位相关的 chunk，提高检索的准确性。</p>
 */
@Entity
@Table(name = "material_summary")
public class MaterialSummaryEntity {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的学习资料 ID */
    @Column(name = "material_id", nullable = false)
    private Long materialId;

    /** 生成摘要的用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 摘要文本内容 */
    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    /** 摘要类型，如 "AUTO" 表示自动生成 */
    @Column(name = "summary_type", nullable = false, length = 40)
    private String summaryType;

    /** 结构化摘要 JSON，包含通用要点、章节脉络等 */
    @Column(name = "structured_json", columnDefinition = "LONGTEXT")
    private String structuredJson;

    /** 摘要来源 JSON，包含可跳转 chunk/page 信息 */
    @Column(name = "sources_json", columnDefinition = "LONGTEXT")
    private String sourcesJson;

    /** 用户在 AI 摘要基础上整理后的个人版本 */
    @Column(name = "user_note", columnDefinition = "TEXT")
    private String userNote;

    /** 生成摘要所使用的模型名称 */
    @Column(name = "model_name", nullable = false, length = 80)
    private String modelName;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * JPA 生命周期回调：在实体首次持久化前自动设置创建时间。
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSummaryText() {
        return summaryText;
    }

    public void setSummaryText(String summaryText) {
        this.summaryText = summaryText;
    }

    public String getSummaryType() {
        return summaryType;
    }

    public void setSummaryType(String summaryType) {
        this.summaryType = summaryType;
    }

    public String getStructuredJson() {
        return structuredJson;
    }

    public void setStructuredJson(String structuredJson) {
        this.structuredJson = structuredJson;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public void setSourcesJson(String sourcesJson) {
        this.sourcesJson = sourcesJson;
    }

    public String getUserNote() {
        return userNote;
    }

    public void setUserNote(String userNote) {
        this.userNote = userNote;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
