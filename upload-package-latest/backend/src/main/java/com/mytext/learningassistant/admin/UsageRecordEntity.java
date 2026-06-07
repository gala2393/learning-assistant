package com.mytext.learningassistant.admin;

import java.time.LocalDateTime;
import java.time.ZoneId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 使用记录实体类。
 * <p>
 * 对应数据库表 {@code usage_record}，用于记录系统中各种用户操作的使用日志。
 * 主要用于记录 RAG 问答等消耗 Token 的操作，方便后续统计和审计。
 * <p>
 * 每条记录包含：
 * <ul>
 *   <li>操作人（userId）和操作类型（action，如 RAG_CHAT、RAG_CHAT_STREAM）</li>
 *   <li>操作目标（targetType + targetId，如 RAG_QUESTION + 问答ID）</li>
 *   <li>使用的模型信息（modelName）和 Token 消耗量（promptTokens / completionTokens / totalTokens）</li>
 *   <li>详细的日志文本（detail）</li>
 *   <li>创建时间（北京时间）</li>
 * </ul>
 */
@Entity
@Table(name = "usage_record")
public class UsageRecordEntity {

    /** 北京时间时区，用于自动设置创建时间 */
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    /** 使用记录主键 ID，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作用户的 ID */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 操作类型，如：
     * <ul>
     *   <li>"RAG_CHAT" - 普通问答</li>
     *   <li>"RAG_CHAT_STREAM" - 流式问答</li>
     * </ul>
     */
    @Column(nullable = false, length = 64)
    private String action;

    /**
     * 操作目标类型，如：
     * <ul>
     *   <li>"RAG_QUESTION" - RAG 问答记录</li>
     * </ul>
     */
    @Column(name = "target_type", nullable = false, length = 64)
    private String targetType;

    /** 操作目标的 ID，例如问答记录 ID */
    @Column(name = "target_id")
    private Long targetId;

    /** 使用的大语言模型名称 */
    @Column(name = "model_name", length = 128)
    private String modelName;

    /** 提示词（输入）消耗的 Token 数量 */
    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    /** 回答（输出）消耗的 Token 数量 */
    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** 总共消耗的 Token 数量（promptTokens + completionTokens） */
    @Column(name = "total_tokens")
    private Integer totalTokens;

    /** 详细日志信息，包含模型名、Token 数、模式等关键参数 */
    @Column(columnDefinition = "TEXT")
    private String detail;

    /** 记录创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * JPA 生命周期回调：在实体首次持久化到数据库之前自动执行。
     * <p>
     * 如果创建时间未设置，则自动填充为当前北京时间。
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(BEIJING_ZONE);
        }
    }

    /**
     * 获取记录 ID。
     *
     * @return 记录 ID
     */
    public Long getId() {
        return id;
    }

    /**
     * 获取操作用户 ID。
     *
     * @return 用户 ID
     */
    public Long getUserId() {
        return userId;
    }

    /**
     * 设置操作用户 ID。
     *
     * @param userId 用户 ID
     */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /**
     * 获取操作类型。
     *
     * @return 操作类型字符串
     */
    public String getAction() {
        return action;
    }

    /**
     * 设置操作类型。
     *
     * @param action 操作类型字符串
     */
    public void setAction(String action) {
        this.action = action;
    }

    /**
     * 获取操作目标类型。
     *
     * @return 目标类型字符串
     */
    public String getTargetType() {
        return targetType;
    }

    /**
     * 设置操作目标类型。
     *
     * @param targetType 目标类型字符串
     */
    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    /**
     * 获取操作目标 ID。
     *
     * @return 目标 ID
     */
    public Long getTargetId() {
        return targetId;
    }

    /**
     * 设置操作目标 ID。
     *
     * @param targetId 目标 ID
     */
    public void setTargetId(Long targetId) {
        this.targetId = targetId;
    }

    /**
     * 获取使用的模型名称。
     *
     * @return 模型名称
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * 设置使用的模型名称。
     *
     * @param modelName 模型名称
     */
    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    /**
     * 获取提示词消耗的 Token 数。
     *
     * @return 提示词 Token 数
     */
    public Integer getPromptTokens() {
        return promptTokens;
    }

    /**
     * 设置提示词消耗的 Token 数。
     *
     * @param promptTokens 提示词 Token 数
     */
    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    /**
     * 获取回答消耗的 Token 数。
     *
     * @return 回答 Token 数
     */
    public Integer getCompletionTokens() {
        return completionTokens;
    }

    /**
     * 设置回答消耗的 Token 数。
     *
     * @param completionTokens 回答 Token 数
     */
    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    /**
     * 获取总 Token 数。
     *
     * @return 总 Token 数
     */
    public Integer getTotalTokens() {
        return totalTokens;
    }

    /**
     * 设置总 Token 数。
     *
     * @param totalTokens 总 Token 数
     */
    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    /**
     * 获取详细日志信息。
     *
     * @return 日志文本
     */
    public String getDetail() {
        return detail;
    }

    /**
     * 设置详细日志信息。
     *
     * @param detail 日志文本
     */
    public void setDetail(String detail) {
        this.detail = detail;
    }

    /**
     * 获取记录创建时间。
     *
     * @return 创建时间
     */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
