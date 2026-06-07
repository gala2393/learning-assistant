package com.mytext.learningassistant.rag;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评估套件运行记录数据访问接口 —— 提供对 {@link RagEvaluationSuiteRunEntity} 的数据库操作。
 */
public interface RagEvaluationSuiteRunRepository extends JpaRepository<RagEvaluationSuiteRunEntity, Long> {

    /**
     * 查询指定套件和用户的所有运行记录（按创建时间倒序）。
     *
     * @param suiteId 套件 ID
     * @param userId  用户 ID
     * @return 运行记录列表
     */
    List<RagEvaluationSuiteRunEntity> findBySuiteIdAndUserIdOrderByCreatedAtDesc(Long suiteId, Long userId);

    /**
     * 查询指定套件和用户的最新一次运行记录。
     *
     * @param suiteId 套件 ID
     * @param userId  用户 ID
     * @return 最新运行记录的 Optional 包装
     */
    Optional<RagEvaluationSuiteRunEntity> findFirstBySuiteIdAndUserIdOrderByCreatedAtDesc(Long suiteId, Long userId);

    /**
     * 删除指定套件下的所有运行记录。
     *
     * @param suiteId 套件 ID
     */
    void deleteBySuiteId(Long suiteId);
}
