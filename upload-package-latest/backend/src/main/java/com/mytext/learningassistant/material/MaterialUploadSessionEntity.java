package com.mytext.learningassistant.material;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 分片上传会话实体类。
 *
 * 映射到数据库表 {@code material_upload_session}，用于管理大文件的分片上传过程。
 * 前端先创建上传会话，然后逐个分片上传文件内容，后端在所有分片到齐后合并文件并执行解析。
 *
 * <p>生命周期：
 * <ol>
 *   <li>创建会话 -> 状态为 UPLOADING</li>
 *   <li>分片逐个上传 -> uploadedChunks 递增</li>
 *   <li>所有分片到齐 -> 状态变为 PROCESSING，触发异步合并和解析</li>
 *   <li>解析成功 -> SUCCESS；解析失败 -> FAILED</li>
 * </ol>
 */
@Entity
@Table(name = "material_upload_session")
public class MaterialUploadSessionEntity {

    /** 会话唯一标识（UUID 格式） */
    @Id
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 客户端生成的上传标识，用于幂等去重 */
    @Column(name = "client_upload_id", nullable = false, length = 64)
    private String clientUploadId;

    /** 上传者的用户 ID */
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    /** 资料标题 */
    @Column(nullable = false, length = 128)
    private String title;

    /** 来源文件类型 */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private MaterialSourceType sourceType;

    /** 原始文件名 */
    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    /** 来源 URL（可选） */
    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    /** 文件总大小（字节） */
    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    /** 每个分片的大小（字节） */
    @Column(name = "chunk_size", nullable = false)
    private Integer chunkSize;

    /** 总分片数 */
    @Column(name = "total_chunks", nullable = false)
    private Integer totalChunks;

    /** 已上传的分片数 */
    @Column(name = "uploaded_chunks", nullable = false)
    private Integer uploadedChunks;

    /** 合并后文件的存储路径 */
    @Column(name = "storage_path", nullable = false, length = 512)
    private String storagePath;

    /** 整个文件的 SHA-256 校验值（可选） */
    @Column(name = "checksum_sha256", length = 128)
    private String checksumSha256;

    /** 会话当前状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private MaterialUploadSessionStatus status;

    /** 关联的学习资料 ID */
    @Column(name = "material_id")
    private Long materialId;

    /** 失败时的错误信息 */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA 生命周期回调：首次持久化前设置默认值。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (status == null) {
            status = MaterialUploadSessionStatus.UPLOADING;
        }
        if (uploadedChunks == null) {
            uploadedChunks = 0;
        }
    }

    /**
     * JPA 生命周期回调：每次更新前刷新更新时间。
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ======================== Getter / Setter ========================

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getClientUploadId() {
        return clientUploadId;
    }

    public void setClientUploadId(String clientUploadId) {
        this.clientUploadId = clientUploadId;
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

    public Integer getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(Integer chunkSize) {
        this.chunkSize = chunkSize;
    }

    public Integer getTotalChunks() {
        return totalChunks;
    }

    public void setTotalChunks(Integer totalChunks) {
        this.totalChunks = totalChunks;
    }

    public Integer getUploadedChunks() {
        return uploadedChunks;
    }

    public void setUploadedChunks(Integer uploadedChunks) {
        this.uploadedChunks = uploadedChunks;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public void setChecksumSha256(String checksumSha256) {
        this.checksumSha256 = checksumSha256;
    }

    public MaterialUploadSessionStatus getStatus() {
        return status;
    }

    public void setStatus(MaterialUploadSessionStatus status) {
        this.status = status;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
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
