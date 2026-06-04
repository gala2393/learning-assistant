package com.mytext.learningassistant.rag;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评估套件用例数据访问接口 —— 提供对 {@link RagEvaluationSuiteCaseEntity} 的数据库操作。
 */
public interface RagEvaluationSuiteCaseRepository extends JpaRepository<RagEvaluationSuiteCaseEntity, Long> {

    /**
     * 查询指定套件的所有用例（按用例序号升序排列）。
     *
     * @param suiteId 评估套件 ID
     * @return 用例列表
     */
    List<RagEvaluationSuiteCaseEntity> findBySuiteIdOrderByCaseIndexAsc(Long suiteId);

    /**
     * 删除指定套件下的所有用例。
     *
     * @param suiteId 评估套件 ID
     */
    void deleteBySuiteId(Long suiteId);
}
