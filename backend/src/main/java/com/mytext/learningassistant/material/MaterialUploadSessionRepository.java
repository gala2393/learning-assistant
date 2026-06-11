package com.mytext.learningassistant.material;

import java.util.Optional;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分片上传会话数据访问接口。
 *
 * 提供对 {@link MaterialUploadSessionEntity} 的 CRUD 操作。
 */
public interface MaterialUploadSessionRepository extends JpaRepository<MaterialUploadSessionEntity, String> {

    /**
     * 根据所有者 ID 和客户端上传标识查找已有的上传会话。
     * 用于实现幂等性：同一用户对同一 clientUploadId 的重复请求不会创建新会话。
     *
     * @param ownerId        用户 ID
     * @param clientUploadId 客户端上传标识
     * @return 匹配的上传会话（可能为空）
     */
    Optional<MaterialUploadSessionEntity> findByOwnerIdAndClientUploadId(Long ownerId, String clientUploadId);

    /**
     * 查找已经进入处理态但尚未完成的上传会话。
     *
     * <p>分片到齐后的合并动作由后台线程执行；如果服务在 PROCESSING 阶段重启，
     * 需要通过该查询把会话重新调度起来，避免前端一直看到“处理中”但实际无人处理。</p>
     */
    List<MaterialUploadSessionEntity> findTop20ByStatusOrderByUpdatedAtAsc(MaterialUploadSessionStatus status);

    /**
     * 删除指定资料关联的所有上传会话。
     *
     * <p>资料删除后，如果保留旧的 SUCCESS 会话，用户再次上传同一个文件时会命中
     * clientUploadId 幂等逻辑，前端会以为上传已经完成而不会重新发送分片。</p>
     *
     * @param materialId 资料 ID
     */
    /** 批量删除资料关联上传会话，确保删除资料后同一文件可以重新上传并重新分片。 */
    @Modifying
    @Query("delete from MaterialUploadSessionEntity session where session.materialId = :materialId")
    @Transactional
    void deleteByMaterialId(@Param("materialId") Long materialId);
}
