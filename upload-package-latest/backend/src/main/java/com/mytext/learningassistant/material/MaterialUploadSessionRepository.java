package com.mytext.learningassistant.material;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

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
}
