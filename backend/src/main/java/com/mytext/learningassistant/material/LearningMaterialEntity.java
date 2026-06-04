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

/**
 * 学习资料实体类。
 *
 * 映射到数据库表 {@code learning_material}，记录一份学习资料的完整生命周期信息，
 * 包括文件元数据、解析状态、摘要状态、预览状态等。
 *
 * <p>典型使用场景：
 * <ul>
 *   <li>用户上传文件或导入网页时创建记录</li>
 *   <li>后台异步解析过程中持续更新 parseStatus / parseProgressPercent</li>
 *   <li>解析完成后 chunkCount 和 pageCount 被写入</li>
 * </ul>
 */
@Entity
@Table(name = "learning_material")
public class LearningMaterialEntity {

    /** 北京时区，用于统一创建和更新时间的时区 */
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    /** 资料主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 资料所有者的用户 ID */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 资料标题（可由用户自定义，也可自动生成） */
    @Column(nullable = false, length = 128)
    private String title;

    /** 来源文件类型（PDF / DOCX / TXT 等） */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private MaterialSourceType sourceType;

    /** 用户上传时的原始文件名 */
    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    /** 文件在服务端存储的相对路径 */
    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    /** 来源 URL（网页导入时记录原始网址） */
    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    /** 文件大小（字节） */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 后台文本解析的状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "parse_status", nullable = false, length = 20)
    private MaterialParseStatus parseStatus;

    /** 解析进度百分比（0 ~ 100） */
    @Column(name = "parse_progress_percent", nullable = false)
    private Integer parseProgressPercent;

    /** 当前解析阶段的简短描述（如"提取文本"、"生成索引"） */
    @Column(name = "parse_stage", length = 80)
    private String parseStage;

    /** 当前解析阶段的详细说明消息 */
    @Column(name = "parse_message", length = 255)
    private String parseMessage;

    /** AI 摘要的生成状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "summary_status", nullable = false, length = 20)
    private MaterialSummaryStatus summaryStatus;

    /** 阅读预览的生成状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "preview_status", nullable = false, length = 20)
    private MaterialPreviewStatus previewStatus;

    /** 预览生成失败时的错误信息 */
    @Column(name = "preview_error", columnDefinition = "TEXT")
    private String previewError;

    /** 文档总页数（PDF 等分页格式有值） */
    @Column(name = "page_count")
    private Integer pageCount;

    /** 知识片段（Chunk）的数量 */
    @Column(name = "chunk_count", nullable = false)
    private Integer chunkCount;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA 生命周期回调：首次持久化前设置默认值。
     * 包括创建/更新时间、解析状态、摘要状态、预览状态等。
     */
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

    /**
     * JPA 生命周期回调：每次更新前刷新更新时间。
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(BEIJING_ZONE);
    }

    // ======================== Getter / Setter ========================

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
