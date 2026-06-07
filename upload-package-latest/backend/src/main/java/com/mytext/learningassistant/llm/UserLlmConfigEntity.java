package com.mytext.learningassistant.llm;

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
 * 用户自定义大模型（LLM）配置的数据库实体类。
 *
 * <p>职责：
 * <ul>
 *   <li>映射数据库表 {@code user_llm_config}，存储每位用户自定义的 LLM 接入信息。</li>
 *   <li>每条记录包含 API 地址、密钥、模型名称等关键参数，以及是否启用、是否为当前激活配置等状态标志。</li>
 *   <li>自动维护创建时间和更新时间（北京时间）。</li>
 * </ul>
 */
@Entity
@Table(name = "user_llm_config")
public class UserLlmConfigEntity {

    /** 北京时区常量，用于记录创建时间和更新时间 */
    private static final ZoneId BEIJING_ZONE = ZoneId.of("Asia/Shanghai");

    /** 主键 ID，由数据库自动生成 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID，不能为空 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 是否启用该配置（用户可以临时禁用某条配置而不删除它） */
    @Column(nullable = false)
    private boolean enabled;

    /** 是否为当前激活的配置（同一用户同一时间只应有一条 active=true 的配置） */
    @Column(name = "active", nullable = false)
    private boolean active;

    /** 用户自定义的配置显示名称，方便在前端列表中区分多条配置 */
    @Column(name = "display_name", length = 128)
    private String displayName;

    /** LLM 服务的 API 基础地址，例如 https://api.openai.com/v1 */
    @Column(name = "base_url", length = 512)
    private String baseUrl;

    /** 调用 LLM 服务所需的 API 密钥，以 TEXT 类型存储以支持较长的密钥 */
    @Column(name = "api_key", columnDefinition = "TEXT")
    private String apiKey;

    /** 要使用的模型名称，例如 gpt-4o、deepseek-chat 等 */
    @Column(name = "model", length = 128)
    private String model;

    /** 记录创建时间，首次持久化时自动填充 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 记录最后更新时间，每次更新时自动刷新 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * JPA 生命周期回调：在实体首次持久化（INSERT）之前执行。
     * 自动设置创建时间和更新时间为当前北京时间。
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now(BEIJING_ZONE);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    /**
     * JPA 生命周期回调：在实体更新（UPDATE）之前执行。
     * 自动将更新时间刷新为当前北京时间。
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now(BEIJING_ZONE);
    }

    // ========== Getter / Setter 方法 ==========

    /** 获取主键 ID */
    public Long getId() {
        return id;
    }

    /** 获取所属用户 ID */
    public Long getUserId() {
        return userId;
    }

    /** 设置所属用户 ID */
    public void setUserId(Long userId) {
        this.userId = userId;
    }

    /** 判断该配置是否启用 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 设置该配置是否启用 */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 判断该配置是否为当前激活配置 */
    public boolean isActive() {
        return active;
    }

    /** 设置该配置是否为当前激活配置 */
    public void setActive(boolean active) {
        this.active = active;
    }

    /** 获取用户自定义的显示名称 */
    public String getDisplayName() {
        return displayName;
    }

    /** 设置用户自定义的显示名称 */
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /** 获取 LLM 服务的 API 基础地址 */
    public String getBaseUrl() {
        return baseUrl;
    }

    /** 设置 LLM 服务的 API 基础地址 */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** 获取 API 密钥 */
    public String getApiKey() {
        return apiKey;
    }

    /** 设置 API 密钥 */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /** 获取模型名称 */
    public String getModel() {
        return model;
    }

    /** 设置模型名称 */
    public void setModel(String model) {
        this.model = model;
    }

    /** 获取记录创建时间 */
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** 获取记录最后更新时间 */
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
