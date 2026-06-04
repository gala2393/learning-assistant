package com.mytext.learningassistant.material;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 学习资料数据访问接口。
 *
 * 提供对 {@link LearningMaterialEntity} 的 CRUD 操作以及常用的查询方法。
 */
public interface LearningMaterialRepository extends JpaRepository<LearningMaterialEntity, Long> {

    /**
     * 查询所有学习资料，按创建时间降序排列。
     *
     * @return 资料列表（最新的排在前面）
     */
    List<LearningMaterialEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 根据所有者 ID 查询其拥有的学习资料，按创建时间降序排列。
     *
     * @param ownerId 资料所有者的用户 ID
     * @return 该用户拥有的资料列表
     */
    List<LearningMaterialEntity> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    /**
     * 根据资料 ID 和所有者 ID 查询单条资料（确保只能访问自己的资料）。
     *
     * @param id      资料 ID
     * @param ownerId 所有者用户 ID
     * @return 匹配的资料实体（可能为空）
     */
    Optional<LearningMaterialEntity> findByIdAndOwnerId(Long id, Long ownerId);
}
