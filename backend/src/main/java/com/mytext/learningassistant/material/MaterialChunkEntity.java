package com.mytext.learningassistant.material;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
