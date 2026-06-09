package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;
import java.time.ZoneId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * 会话长期记忆实体。
 *
 * <p>每个用户的每个 conversationId 对应一条记录，用来保存较早轮次的压缩摘要。
 * 问答请求只携带这份摘要和最近若干轮原文，避免随着对话轮数增长而持续变慢。</p>
 */
@Entity
@Table(name = "rag_conversation_memory")
public class RagConversationMemoryEntity {

    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    /**
     * 较早轮次的滚动摘要。
     *
     * <p>摘要由本地规则维护，保留用户问题、助手结论和关键上下文，
     * 不额外调用模型，避免为了维护记忆再增加一次接口耗时。</p>
     */
    @Column(name = "summary", columnDefinition = "LONGTEXT")
    private String summary;

    /**
     * 已经写入 summary 的最后一条问答 ID。
     *
     * <p>后续更新只处理这个 ID 之后、且已经滑出最近原文窗口的问答，
     * 防止摘要重复追加同一轮内容。</p>
     */
    @Column(name = "summarized_question_id")
    private Long summarizedQuestionId;

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

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public void setConversationId(Long conversationId) {
        this.conversationId = conversationId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Long getSummarizedQuestionId() {
        return summarizedQuestionId;
    }

    public void setSummarizedQuestionId(Long summarizedQuestionId) {
        this.summarizedQuestionId = summarizedQuestionId;
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
