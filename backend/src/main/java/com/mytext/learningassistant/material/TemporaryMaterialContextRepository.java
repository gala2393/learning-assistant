package com.mytext.learningassistant.material;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 临时资料全文上下文仓库。
 *
 * <p>所有读取都带 ownerId，保证用户只能恢复自己上传到智能问答的临时资料。</p>
 */
public interface TemporaryMaterialContextRepository extends JpaRepository<TemporaryMaterialContextEntity, String> {

    /**
     * 按临时资料 ID 和用户 ID 查询全文上下文。
     *
     * @param id      临时资料 ID
     * @param ownerId 用户 ID
     * @return 当前用户可访问的临时资料上下文
     */
    Optional<TemporaryMaterialContextEntity> findByIdAndOwnerId(String id, Long ownerId);

    /**
     * 删除超过保留期限的临时资料全文，避免“临时”附件长期占用数据库。
     *
     * @param cutoff 清理截止时间
     * @return 删除行数
     */
    @Modifying
    @Transactional
    @Query("delete from TemporaryMaterialContextEntity context where context.createdAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
