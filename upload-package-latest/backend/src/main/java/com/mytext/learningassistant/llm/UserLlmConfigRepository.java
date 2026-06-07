package com.mytext.learningassistant.llm;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户自定义 LLM 配置的数据访问接口（Repository）。
 *
 * <p>职责：
 * <ul>
 *   <li>继承 {@link JpaRepository}，提供对 {@code user_llm_config} 表的 CRUD 操作。</li>
 *   <li>定义按用户 ID 查询配置的自定义查询方法，支持查找单条、列表、以及激活状态的配置。</li>
 * </ul>
 */
public interface UserLlmConfigRepository extends JpaRepository<UserLlmConfigEntity, Long> {

    /**
     * 根据用户 ID 查找该用户的一条 LLM 配置（如果有多个则返回第一个）。
     *
     * @param userId 用户 ID
     * @return 匹配的配置实体，如果不存在则返回空
     */
    Optional<UserLlmConfigEntity> findByUserId(Long userId);

    /**
     * 根据用户 ID 查找该用户的所有 LLM 配置，按更新时间降序排列（最新的在前）。
     *
     * @param userId 用户 ID
     * @return 配置列表，按更新时间从新到旧排序
     */
    List<UserLlmConfigEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 根据配置 ID 和用户 ID 联合查找，用于确保用户只能操作自己的配置（权限校验）。
     *
     * @param id     配置 ID
     * @param userId 用户 ID
     * @return 匹配的配置实体，如果不存在则返回空
     */
    Optional<UserLlmConfigEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 查找指定用户当前激活的（active=true）且最新的 LLM 配置。
     * 用于在调用 LLM 时快速获取用户当前正在使用的配置。
     *
     * @param userId 用户 ID
     * @return 当前激活的配置实体，如果没有激活配置则返回空
     */
    Optional<UserLlmConfigEntity> findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(Long userId);
}
