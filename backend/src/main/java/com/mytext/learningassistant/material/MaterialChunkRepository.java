package com.mytext.learningassistant.material;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MaterialChunkRepository extends JpaRepository<MaterialChunkEntity, Long> {

    List<MaterialChunkEntity> findByMaterialIdOrderByChunkIndexAsc(Long materialId);

    void deleteByMaterialId(Long materialId);
}
