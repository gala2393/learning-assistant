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
import jakarta.persistence.Table;

/**
 * 知识片段（Chunk）实体类。
 *
 * 映射到数据库表 {@code material_chunk}，存储学习资料经过文本分块后得到的单个知识片段。
 * 每个片段除了保存原始文本外，还保存了该片段的 Embedding 向量（JSON 格式）、
 * 摘要、关键词、章节标题、层次路径等元数据，用于支持语义检索和前端展示。
 */
@Entity
@Table(name = "material_chunk")
public class MaterialChunkEntity {

    /** 片段主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属学习资料的 ID */
    @Column(name = "material_id", nullable = false)
    private Long materialId;

    /** 片段在资料中的序号（从 0 开始） */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /** 片段的完整文本内容 */
    @Column(name = "chunk_text", columnDefinition = "TEXT", nullable = false)
    private String chunkText;

    /** 片段所在的页码（PDF 等分页文档有值） */
    @Column(name = "page_no")
    private Integer pageNo;

    /** 片段来源起始页，页码从 1 开始。 */
    @Column(name = "source_page_start")
    private Integer sourcePageStart;

    /** 片段来源结束页，默认与起始页相同。 */
    @Column(name = "source_page_end")
    private Integer sourcePageEnd;

    /** 片段所在章节的标题 */
    @Column(name = "section_title", length = 255)
    private String sectionTitle;

    /**
     * 层次路径，表示该片段在资料结构中的位置。
     * 例如："数据结构教材 > 第3章 树 > 切片12"
     */
    @Column(name = "hierarchy_path", length = 500)
    private String hierarchyPath;

    /** 片段摘要（通常取文本前 180 个字符左右的第一个完整句子） */
    @Column(name = "summary", length = 500)
    private String summary;

    /** 片段关键词（逗号分隔，最多 8 个，按词频排序） */
    @Column(name = "keywords", length = 500)
    private String keywords;

    /**
     * 片段文本的 Embedding 向量，以 JSON 数组字符串形式存储。
     * 例如："[0.123, -0.456, 0.789, ...]"
     * 用于语义相似度检索。
     */
    @Column(name = "embedding_json", columnDefinition = "TEXT")
    private String embeddingJson;

    /** 片段字符数，用于索引进度和切片质量检查。 */
    @Column(name = "char_count", nullable = false)
    private Integer charCount;

    /** 粗略 token 数，用中文/英文混合场景的估算值即可。 */
    @Column(name = "token_count", nullable = false)
    private Integer tokenCount;

    /** Embedding 生成状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "embedding_status", nullable = false, length = 20)
    private MaterialIndexStatus embeddingStatus;

    /** BM25/数据库检索索引状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "index_status", nullable = false, length = 20)
    private MaterialIndexStatus indexStatus;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (charCount == null) {
            charCount = chunkText == null ? 0 : chunkText.length();
        }
        if (tokenCount == null) {
            tokenCount = Math.max(1, (int) Math.ceil(charCount / 1.8));
        }
        if (embeddingStatus == null) {
            embeddingStatus = embeddingJson == null || embeddingJson.isBlank()
                ? MaterialIndexStatus.PENDING
                : MaterialIndexStatus.READY;
        }
        if (indexStatus == null) {
            indexStatus = MaterialIndexStatus.READY;
        }
    }

    // ======================== Getter / Setter ========================

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

    public Integer getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(Integer chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public String getChunkText() {
        return chunkText;
    }

    public void setChunkText(String chunkText) {
        this.chunkText = chunkText;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public Integer getSourcePageStart() {
        return sourcePageStart;
    }

    public void setSourcePageStart(Integer sourcePageStart) {
        this.sourcePageStart = sourcePageStart;
    }

    public Integer getSourcePageEnd() {
        return sourcePageEnd;
    }

    public void setSourcePageEnd(Integer sourcePageEnd) {
        this.sourcePageEnd = sourcePageEnd;
    }

    public String getSectionTitle() {
        return sectionTitle;
    }

    public void setSectionTitle(String sectionTitle) {
        this.sectionTitle = sectionTitle;
    }

    public String getHierarchyPath() {
        return hierarchyPath;
    }

    public void setHierarchyPath(String hierarchyPath) {
        this.hierarchyPath = hierarchyPath;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getKeywords() {
        return keywords;
    }

    public void setKeywords(String keywords) {
        this.keywords = keywords;
    }

    public String getEmbeddingJson() {
        return embeddingJson;
    }

    public void setEmbeddingJson(String embeddingJson) {
        this.embeddingJson = embeddingJson;
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

    public MaterialIndexStatus getEmbeddingStatus() {
        return embeddingStatus;
    }

    public void setEmbeddingStatus(MaterialIndexStatus embeddingStatus) {
        this.embeddingStatus = embeddingStatus;
    }

    public MaterialIndexStatus getIndexStatus() {
        return indexStatus;
    }

    public void setIndexStatus(MaterialIndexStatus indexStatus) {
        this.indexStatus = indexStatus;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
