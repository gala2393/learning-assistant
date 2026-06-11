package com.mytext.learningassistant.material;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资料页级文本仓库。
 */
public interface MaterialPageRepository extends JpaRepository<MaterialPageEntity, Long> {

    List<MaterialPageEntity> findByMaterialIdOrderByPageNoAsc(Long materialId);

    Optional<MaterialPageEntity> findByMaterialIdAndPageNo(Long materialId, Integer pageNo);

    /** 删除某份资料的全部页级预览记录，重新解析或删除资料时使用独立事务提交。 */
    /** 批量删除某份资料的页记录，避免大 PDF 按实体逐条删除造成锁等待。 */
    @Modifying
    @Query("delete from MaterialPageEntity page where page.materialId = :materialId")
    @Transactional
    void deleteByMaterialId(@Param("materialId") Long materialId);
}
