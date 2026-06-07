package com.mytext.learningassistant.rag;

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
 * RAG 问答记录实体 —— 对应数据库中的 {@code rag_question} 表。
 *
 * <p>这是 RAG 系统的核心数据实体，存储每一次用户提问和 AI 回答的完整记录。
 * 每条记录包含：</p>
 * <ul>
 *   <li>用户的问题文本</li>
 *   <li>AI 生成的回答文本</li>
 *   <li>使用的模型名称和 token 用量</li>
 *   <li>处理状态（INIT -> RUNNING -> SUCCESS/FAILED）</li>
 *   <li>会话归属（通过 conversationId 实现多轮对话）</li>
 * </ul>
 *
 * <p>多轮对话机制：同一会话中的多条记录共享同一个 conversationId，
 * 新消息会自动归入已有的会话，方便历史回顾和上下文管理。</p>
 */
@Entity
@Table(name = "rag_question")
public class RagQuestionEntity {

    /** 北京时间时区，用于统一时间格式 */
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    /** 主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 会话 ID（同一会话中的多条记录共享此值） */
    @Column(name = "conversation_id")
    private Long conversationId;

    /** 用户的问题文本 */
    @Column(name = "question_text", columnDefinition = "TEXT", nullable = false)
    private String questionText;

    /** 用户随问题上传的图片 JSON，仅用于历史回显 */
    @Column(name = "question_images_json", columnDefinition = "LONGTEXT")
    private String questionImagesJson;

    /** 用户随问题上传的临时资料 JSON，仅用于历史回显，不进入资料管理 */
    @Column(name = "question_temporary_material_json", columnDefinition = "LONGTEXT")
    private String questionTemporaryMaterialJson;

    /** 会话标题（通常取自第一条问题的截断文本） */
    @Column(name = "title", columnDefinition = "TEXT")
    private String title;

    /** 是否置顶 */
    @Column(name = "pinned", nullable = false)
    private boolean pinned;

    /** AI 生成的回答文本 */
    @Column(name = "answer_text", columnDefinition = "TEXT", nullable = false)
    private String answerText;

    /** 使用的模型名称 */
    @Column(name = "model_name", nullable = false, length = 64)
    private String modelName;

    /** 输入 prompt 的 token 用量 */
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    /** 生成回答的 token 用量 */
    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** 总 token 用量 */
    @Column(name = "total_tokens")
    private Integer totalTokens;

    /** 是否使用了用户自定义模型（区别于系统默认模型） */
    @Column(name = "custom_model", nullable = false)
    private boolean customModel;

    /** 问答处理状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "question_status", nullable = false, length = 20)
    private QuestionStatus questionStatus;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 最后更新时间 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA 生命周期回调：在实体首次持久化前设置创建时间、更新时间和初始状态。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(BEIJING_ZONE);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (questionStatus == null) {
            questionStatus = QuestionStatus.INIT;
        }
    }

    /**
     * JPA 生命周期回调：在实体更新前刷新更新时间。
     */
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

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public String getQuestionImagesJson() {
        return questionImagesJson;
    }

    public void setQuestionImagesJson(String questionImagesJson) {
        this.questionImagesJson = questionImagesJson;
    }

    public String getQuestionTemporaryMaterialJson() {
        return questionTemporaryMaterialJson;
    }

    public void setQuestionTemporaryMaterialJson(String questionTemporaryMaterialJson) {
        this.questionTemporaryMaterialJson = questionTemporaryMaterialJson;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public String getAnswerText() {
        return answerText;
    }

    public void setAnswerText(String answerText) {
        this.answerText = answerText;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public boolean isCustomModel() {
        return customModel;
    }

    public void setCustomModel(boolean customModel) {
        this.customModel = customModel;
    }

    public QuestionStatus getQuestionStatus() {
        return questionStatus;
    }

    public void setQuestionStatus(QuestionStatus questionStatus) {
        this.questionStatus = questionStatus;
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
