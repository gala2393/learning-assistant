package com.mytext.learningassistant.material;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * 资料页面文本层块实体。
 *
 * <p>阅读器需要把可选中文本直接覆盖在页面视觉层上，而不是在页面下方再放一份解析正文。
 * 这个实体保存每个页面上的文本和坐标，MinerU 可写入精确 bbox；旧解析器没有坐标时会写入
 * 归一化的整页文本块，前端仍可作为兜底文本层使用。
 */
@Entity
@Table(name = "material_page_text_block")
public class MaterialPageTextBlockEntity {

    /** 文本块主键 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属资料 ID */
    @Column(name = "material_id", nullable = false)
    private Long materialId;

    /** 页码，从 1 开始 */
    @Column(name = "page_no", nullable = false)
    private Integer pageNo;

    /** 页内阅读顺序，从 0 开始 */
    @Column(name = "block_index", nullable = false)
    private Integer blockIndex;

    /** 文本内容 */
    @Column(name = "text", nullable = false, columnDefinition = "TEXT")
    private String text;

    /** 文本块类型，例如 paragraph、title、line、ocr */
    @Column(name = "block_type", length = 40)
    private String blockType;

    /** 坐标来源，例如 MINERU、PDFBOX、LEGACY */
    @Column(name = "source", length = 40)
    private String source;

    /** 关联的知识片段 ID；暂无精确映射时为空 */
    @Column(name = "chunk_id")
    private Long chunkId;

    /** 页面宽度，通常与预览 PDF 坐标系一致 */
    @Column(name = "page_width")
    private Double pageWidth;

    /** 页面高度，通常与预览 PDF 坐标系一致 */
    @Column(name = "page_height")
    private Double pageHeight;

    /** bbox 左上角 X */
    @Column(name = "bbox_x")
    private Double bboxX;

    /** bbox 左上角 Y */
    @Column(name = "bbox_y")
    private Double bboxY;

    /** bbox 宽度 */
    @Column(name = "bbox_width")
    private Double bboxWidth;

    /** bbox 高度 */
    @Column(name = "bbox_height")
    private Double bboxHeight;

    /** 识别置信度，MinerU/OCR 可填；旧解析器为空 */
    @Column(name = "confidence")
    private Double confidence;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public void setMaterialId(Long materialId) {
        this.materialId = materialId;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public Integer getBlockIndex() {
        return blockIndex;
    }

    public void setBlockIndex(Integer blockIndex) {
        this.blockIndex = blockIndex;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getBlockType() {
        return blockType;
    }

    public void setBlockType(String blockType) {
        this.blockType = blockType;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Long getChunkId() {
        return chunkId;
    }

    public void setChunkId(Long chunkId) {
        this.chunkId = chunkId;
    }

    public Double getPageWidth() {
        return pageWidth;
    }

    public void setPageWidth(Double pageWidth) {
        this.pageWidth = pageWidth;
    }

    public Double getPageHeight() {
        return pageHeight;
    }

    public void setPageHeight(Double pageHeight) {
        this.pageHeight = pageHeight;
    }

    public Double getBboxX() {
        return bboxX;
    }

    public void setBboxX(Double bboxX) {
        this.bboxX = bboxX;
    }

    public Double getBboxY() {
        return bboxY;
    }

    public void setBboxY(Double bboxY) {
        this.bboxY = bboxY;
    }

    public Double getBboxWidth() {
        return bboxWidth;
    }

    public void setBboxWidth(Double bboxWidth) {
        this.bboxWidth = bboxWidth;
    }

    public Double getBboxHeight() {
        return bboxHeight;
    }

    public void setBboxHeight(Double bboxHeight) {
        this.bboxHeight = bboxHeight;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
