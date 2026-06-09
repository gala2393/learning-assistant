package com.mytext.learningassistant.material;

/**
 * 页面文本层块响应。
 *
 * @param id         文本块 ID
 * @param pageNo     页码
 * @param blockIndex 页内顺序
 * @param text       文本内容
 * @param blockType  文本块类型
 * @param source     坐标来源
 * @param chunkId    关联片段 ID
 * @param pageWidth  页面宽度
 * @param pageHeight 页面高度
 * @param bboxX      bbox 左上角 X
 * @param bboxY      bbox 左上角 Y
 * @param bboxWidth  bbox 宽度
 * @param bboxHeight bbox 高度
 * @param confidence 识别置信度
 */
public record MaterialPageTextBlockResponse(
    Long id,
    Integer pageNo,
    Integer blockIndex,
    String text,
    String blockType,
    String source,
    Long chunkId,
    Double pageWidth,
    Double pageHeight,
    Double bboxX,
    Double bboxY,
    Double bboxWidth,
    Double bboxHeight,
    Double confidence
) {
}
