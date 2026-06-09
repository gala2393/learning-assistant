package com.mytext.learningassistant.material;

/**
 * 学习资料来源类型枚举。
 *
 * 区分用户上传的学习资料是哪种文件格式，后端会根据此类型选择不同的文本提取策略。
 */
public enum MaterialSourceType {

    /** PDF 文档 -- 使用 PDFBox 提取文本，支持 OCR */
    PDF,

    /** 旧版 Word 文档（.doc）-- 使用 Apache POI 提取文本，可选用 LibreOffice 生成预览 */
    WORD,

    /** 旧版 PowerPoint 文件（.ppt）-- 当前不支持，请转换为 PPTX */
    PPT,

    /** 网页导入 -- 从 URL 抓取 HTML 内容后提取纯文本 */
    WEB,

    /** 纯文本文件（.txt）-- 直接按编码读取 */
    TXT,

    /** Markdown 文件（.md）-- 当作纯文本处理 */
    MD,

    /** HTML 文件（.html/.htm）-- 去除标签后提取纯文本 */
    HTML,

    /** Word 文档（.docx）-- 使用 Zip 解压解析 XML 提取文本 */
    DOCX,

    /** PowerPoint 文件（.pptx）-- 使用 Zip 解压解析 XML 提取文本 */
    PPTX,

    /** Excel 文件（.xlsx）-- 优先交给 MinerU 解析并生成可选中文本层 */
    XLSX
}
