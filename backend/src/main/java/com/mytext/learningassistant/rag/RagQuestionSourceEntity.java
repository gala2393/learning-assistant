package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * RAG 问答来源实体 —— 对应数据库中的 {@code rag_question_source} 表。
 *
 * <p>记录 AI 回答时引用的资料来源片段。每条问答可以有多个来源，
 * 每个来源指向学习资料中的一个 chunk，并记录该 chunk 的页码、摘要文本和相关性评分。</p>
 *
 * <p>来源信息的作用：</p>
 * <ul>
 *   <li>让用户可以追溯回答的依据，验证信息的准确性</li>
 *   <li>用于评估回答的忠实度和上下文相关性</li>
 *   <li>前端展示"资料依据"，方便用户跳转到原始资料查看上下文</li>
 * </ul>
 */
@Entity
@Table(name = "rag_question_source")
public class RagQuestionSourceEntity {

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联的问答记录 ID */
    @Column(name = "question_id", nullable = false)
    private Long questionId;

    /** 来源资料 ID */
    @Column(name = "material_id", nullable = false)
    private Long materialId;

    /** 来源 chunk ID */
    @Column(name = "chunk_id", nullable = false)
    private Long chunkId;

    /** 来源资料标题 */
    @Column(name = "source_title", nullable = false, length = 255)
    private String sourceTitle;

    /** 来源页码 */
    @Column(name = "page_no")
    private Integer pageNo;

    /** 来源文本摘要（chunk 内容的截取片段） */
    @Column(name = "excerpt", columnDefinition = "TEXT", nullable = false)
    private String excerpt;

    /** 相关性评分（来自检索和重排阶段的分数） */
    @Column(name = "rank_score", nullable = false)
    private Double rankScore;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public String getSourceTitle() {
        return sourceTitle;
    }

    public void setSourceTitle(String sourceTitle) {
        this.sourceTitle = sourceTitle;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public String getExcerpt() {
        return excerpt;
    }

    public void setExcerpt(String excerpt) {
        this.excerpt = excerpt;
    }

    public Double getRankScore() {
        return rankScore;
    }

    public void setRankScore(Double rankScore) {
        this.rankScore = rankScore;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
