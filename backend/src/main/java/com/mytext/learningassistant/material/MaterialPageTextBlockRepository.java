package com.mytext.learningassistant.material;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资料页面文本层数据访问接口。
 */
public interface MaterialPageTextBlockRepository extends JpaRepository<MaterialPageTextBlockEntity, Long> {

    /** 查询某个资料指定页面的文本层块，按页面内阅读顺序返回。 */
    List<MaterialPageTextBlockEntity> findByMaterialIdAndPageNoOrderByBlockIndexAsc(Long materialId, Integer pageNo);

    /** 查询某个资料的全部文本层块，通常用于删除或重新解析前清理。 */
    List<MaterialPageTextBlockEntity> findByMaterialIdOrderByPageNoAscBlockIndexAsc(Long materialId);

    /** 删除某个资料的全部文本层块。 */
    /** 批量删除某份资料的全部文本层块，避免大文档逐条删除导致长事务。 */
    @Modifying
    @Query("delete from MaterialPageTextBlockEntity block where block.materialId = :materialId")
    @Transactional
    void deleteByMaterialId(@Param("materialId") Long materialId);

    /** 删除某个资料指定页的文本层块，供后台 OCR 用新识别结果替换占位文本层。 */
    /** 批量删除单页文本层，供 OCR 页级回填替换旧占位文本层。 */
    @Modifying
    @Query("delete from MaterialPageTextBlockEntity block where block.materialId = :materialId and block.pageNo = :pageNo")
    @Transactional
    void deleteByMaterialIdAndPageNo(@Param("materialId") Long materialId, @Param("pageNo") Integer pageNo);
}
