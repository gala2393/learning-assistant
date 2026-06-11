package com.mytext.learningassistant.rag;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 学习资料摘要数据访问接口 —— 提供对 {@link MaterialSummaryEntity} 的数据库操作。
 *
 * <p>支持按资料 ID 和用户 ID 查询摘要、删除摘要等操作。</p>
 */
public interface MaterialSummaryRepository extends JpaRepository<MaterialSummaryEntity, Long> {

    /**
     * 查询指定资料和用户的最新一条摘要。
     *
     * @param materialId 学习资料 ID
     * @param userId     用户 ID
     * @return 最新摘要的 Optional 包装
     */
    Optional<MaterialSummaryEntity> findFirstByMaterialIdAndUserIdOrderByCreatedAtDesc(long materialId, long userId);

    /**
     * 查询指定资料和用户的所有摘要历史（按创建时间倒序）。
     *
     * @param materialId 学习资料 ID
     * @param userId     用户 ID
     * @return 摘要列表
     */
    List<MaterialSummaryEntity> findByMaterialIdAndUserIdOrderByCreatedAtDesc(long materialId, long userId);

    /**
     * 删除指定资料和用户的所有摘要。
     *
     * @param materialId 学习资料 ID
     * @param userId     用户 ID
     */
    /** 批量删除资料摘要，避免删除资料时先加载历史摘要实体。 */
    @Modifying
    @Query("delete from MaterialSummaryEntity summary where summary.materialId = :materialId and summary.userId = :userId")
    @Transactional
    void deleteByMaterialIdAndUserId(@Param("materialId") long materialId, @Param("userId") long userId);
}
