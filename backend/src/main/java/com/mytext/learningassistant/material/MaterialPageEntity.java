package com.mytext.learningassistant.material;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 资料页级文本实体。
 *
 * <p>这张表保存每一页的抽取结果和状态，便于大 PDF 先开放前若干页，
 * 同时让阅读器在页级文本尚未完成时可以显示“处理中”。</p>
 */
@Entity
@Table(name = "material_page")
public class MaterialPageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "material_id", nullable = false)
    private Long materialId;

    @Column(name = "page_no", nullable = false)
    private Integer pageNo;

    @Column(name = "text", columnDefinition = "MEDIUMTEXT")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(name = "text_status", nullable = false, length = 20)
    private MaterialTextStatus textStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "ocr_status", nullable = false, length = 20)
    private MaterialOcrStatus ocrStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "preview_status", nullable = false, length = 20)
    private MaterialPreviewStatus previewStatus;

    @Column(name = "char_count", nullable = false)
    private Integer charCount;

    @Column(name = "token_count", nullable = false)
    private Integer tokenCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (textStatus == null) {
            textStatus = MaterialTextStatus.PENDING;
        }
        if (ocrStatus == null) {
            ocrStatus = MaterialOcrStatus.DISABLED;
        }
        if (previewStatus == null) {
            previewStatus = MaterialPreviewStatus.NONE;
        }
        if (charCount == null) {
            charCount = 0;
        }
        if (tokenCount == null) {
            tokenCount = 0;
        }
    }

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

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public MaterialTextStatus getTextStatus() {
        return textStatus;
    }

    public void setTextStatus(MaterialTextStatus textStatus) {
        this.textStatus = textStatus;
    }

    public MaterialOcrStatus getOcrStatus() {
        return ocrStatus;
    }

    public void setOcrStatus(MaterialOcrStatus ocrStatus) {
        this.ocrStatus = ocrStatus;
    }

    public MaterialPreviewStatus getPreviewStatus() {
        return previewStatus;
    }

    public void setPreviewStatus(MaterialPreviewStatus previewStatus) {
        this.previewStatus = previewStatus;
    }

    public Integer getCharCount() {
        return charCount;
    }

    public void setCharCount(Integer charCount) {
        this.charCount = charCount;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
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
