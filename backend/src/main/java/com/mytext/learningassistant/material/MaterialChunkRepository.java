package com.mytext.learningassistant.material;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
     * 查询某份资料指定页的切片。
     * 图片型 PDF 的后台 OCR 会按页替换占位切片，单页查询可以避免每批 OCR 都加载整份大资料。
     */
    List<MaterialChunkEntity> findByMaterialIdAndPageNoOrderByChunkIndexAsc(Long materialId, Integer pageNo);

    /**
     * 根据资料 ID 删除该资料的所有知识片段。
     * 通常在重新解析或删除资料时调用。
     *
     * @param materialId 学习资料 ID
     */
    /**
     * 批量删除资料切片。
     * 大文件可能有数千个切片，使用 JPQL delete 避免先加载实体再逐条 remove 导致长事务和锁等待。
     */
    @Modifying
    @Query("delete from MaterialChunkEntity chunk where chunk.materialId = :materialId")
    @Transactional
    void deleteByMaterialId(@Param("materialId") Long materialId);
}
