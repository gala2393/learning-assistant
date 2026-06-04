package com.mytext.learningassistant.material;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 知识片段数据访问接口。
 *
 * 提供对 {@link MaterialChunkEntity} 的 CRUD 操作以及常用的查询方法。
 */
public interface MaterialChunkRepository extends JpaRepository<MaterialChunkEntity, Long> {

    /**
     * 根据资料 ID 查询所有知识片段，按片段序号升序排列。
     *
     * @param materialId 学习资料 ID
     * @return 该资料的所有知识片段列表（有序）
     */
    List<MaterialChunkEntity> findByMaterialIdOrderByChunkIndexAsc(Long materialId);

    /**
     * 根据资料 ID 删除该资料的所有知识片段。
     * 通常在重新解析或删除资料时调用。
     *
     * @param materialId 学习资料 ID
     */
    void deleteByMaterialId(Long materialId);
}
