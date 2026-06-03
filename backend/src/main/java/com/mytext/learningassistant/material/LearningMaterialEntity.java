package com.mytext.learningassistant.material;

import java.time.LocalDateTime;
import java.time.ZoneId;

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

@Entity
@Table(name = "learning_material")
public class LearningMaterialEntity {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 128)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private MaterialSourceType sourceType;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 20)
    private MaterialParseStatus parseStatus;

    @Column(name = "parse_progress_percent", nullable = false)
    private Integer parseProgressPercent;

    @Column(name = "parse_stage", length = 80)
    private String parseStage;

    @Column(name = "parse_message", length = 255)
    private String parseMessage;

    @Enumerated(EnumType.STRING)
    @Column(name = "summary_status", nullable = false, length = 20)
    private MaterialSummaryStatus summaryStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "preview_status", nullable = false, length = 20)
    private MaterialPreviewStatus previewStatus;

    @Column(name = "preview_error", columnDefinition = "TEXT")
    private String previewError;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(BEIJING_ZONE);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (parseStatus == null) {
            parseStatus = MaterialParseStatus.PENDING;
        }
        if (parseProgressPercent == null) {
            parseProgressPercent = 0;
        }
        if (parseStage == null) {
            parseStage = "等待解析";
        }
        if (summaryStatus == null) {
            summaryStatus = MaterialSummaryStatus.PENDING;
        }
        if (previewStatus == null) {
            previewStatus = MaterialPreviewStatus.NONE;
        }
        if (chunkCount == null) {
            chunkCount = 0;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(BEIJING_ZONE);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
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

    public MaterialSourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(MaterialSourceType sourceType) {
        this.sourceType = sourceType;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public MaterialParseStatus getParseStatus() {
        return parseStatus;
    }

    public void setParseStatus(MaterialParseStatus parseStatus) {
        this.parseStatus = parseStatus;
    }

    public Integer getParseProgressPercent() {
        return parseProgressPercent;
    }

    public void setParseProgressPercent(Integer parseProgressPercent) {
        this.parseProgressPercent = parseProgressPercent;
    }

    public String getParseStage() {
        return parseStage;
    }

    public void setParseStage(String parseStage) {
        this.parseStage = parseStage;
    }

    public String getParseMessage() {
        return parseMessage;
    }

    public void setParseMessage(String parseMessage) {
        this.parseMessage = parseMessage;
    }

    public MaterialSummaryStatus getSummaryStatus() {
        return summaryStatus;
    }

    public void setSummaryStatus(MaterialSummaryStatus summaryStatus) {
        this.summaryStatus = summaryStatus;
    }

    public MaterialPreviewStatus getPreviewStatus() {
        return previewStatus;
    }

    public void setPreviewStatus(MaterialPreviewStatus previewStatus) {
        this.previewStatus = previewStatus;
    }

    public String getPreviewError() {
        return previewError;
    }

    public void setPreviewError(String previewError) {
        this.previewError = previewError;
    }

    public Integer getPageCount() {
        return pageCount;
    }

    public void setPageCount(Integer pageCount) {
        this.pageCount = pageCount;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public void setChunkCount(Integer chunkCount) {
        this.chunkCount = chunkCount;
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
