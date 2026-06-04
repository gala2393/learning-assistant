package com.mytext.learningassistant.rag;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 评估套件数据访问接口 —— 提供对 {@link RagEvaluationSuiteEntity} 的数据库操作。
 *
 * <p>支持按用户查询套件列表、按 ID 和用户查找套件、查询到期需要执行的定时套件等。</p>
 */
public interface RagEvaluationSuiteRepository extends JpaRepository<RagEvaluationSuiteEntity, Long> {

    /**
     * 查询指定用户的所有评估套件（按更新时间倒序排列）。
     *
     * @param userId 用户 ID
     * @return 套件列表
     */
    List<RagEvaluationSuiteEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 按 ID 和用户 ID 查找评估套件（确保用户只能访问自己的套件）。
     *
     * @param id     套件 ID
     * @param userId 用户 ID
     * @return 套件的 Optional 包装
     */
    Optional<RagEvaluationSuiteEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 查询所有已启用定时执行且已到期的评估套件（按下次执行时间升序排列）。
     * 调度器会定期调用此方法来发现需要执行的套件。
     *
     * @param now 当前时间
     * @return 到期的套件列表
     */
    List<RagEvaluationSuiteEntity> findByScheduledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(LocalDateTime now);
}
