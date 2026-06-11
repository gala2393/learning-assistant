package com.mytext.learningassistant.material;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 智能问答临时资料全文上下文。
 *
 * <p>临时资料不进入资料管理列表，但仍需要在当前用户后续多轮对话中可恢复全文。
 * 这里保存解析后的完整文本；聊天请求和历史记录只保存轻量引用，避免前端每轮重复上传大段正文。</p>
 */
@Entity
@Table(
    name = "temporary_material_context",
    indexes = {
        @Index(name = "idx_temporary_material_context_owner_created", columnList = "owner_id, created_at"),
        @Index(name = "idx_temporary_material_context_owner_id", columnList = "owner_id, id")
    }
)
public class TemporaryMaterialContextEntity {

    /** 临时资料 ID，直接作为前后端引用键。 */
    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    /** 所属用户 ID，读取时必须一起校验，避免跨用户读取临时资料。 */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 展示标题。 */
    @Column(name = "title", length = 128)
    private String title;

    /** 原始文件名。 */
    @Column(name = "original_name", length = 255)
    private String originalName;

    /** 资料类型，例如 PDF、DOCX、TXT。 */
    @Column(name = "source_type", length = 40)
    private String sourceType;

    /** 解析后的完整正文，供后续每轮问题重新检索。 */
    @Column(name = "text", nullable = false, columnDefinition = "LONGTEXT")
    private String text;

    /** 轻量摘要，用于前端预览和历史回显。 */
    @Column(name = "excerpt", columnDefinition = "TEXT")
    private String excerpt;

    /** 原始文件大小，单位字节。 */
    @Column(name = "file_size")
    private Long fileSize;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public void setExcerpt(String excerpt) {
        this.excerpt = excerpt;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
