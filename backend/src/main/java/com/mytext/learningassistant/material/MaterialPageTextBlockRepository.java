package com.mytext.learningassistant.material;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 资料页面文本层数据访问接口。
 */
public interface MaterialPageTextBlockRepository extends JpaRepository<MaterialPageTextBlockEntity, Long> {

    /** 查询某个资料指定页面的文本层块，按页面内阅读顺序返回。 */
    List<MaterialPageTextBlockEntity> findByMaterialIdAndPageNoOrderByBlockIndexAsc(Long materialId, Integer pageNo);

    /** 查询某个资料的全部文本层块，通常用于删除或重新解析前清理。 */
    List<MaterialPageTextBlockEntity> findByMaterialIdOrderByPageNoAscBlockIndexAsc(Long materialId);

    /** 删除某个资料的全部文本层块。 */
    void deleteByMaterialId(Long materialId);
}
