package com.mytext.learningassistant.rag;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 智能问答临时资料附件。
 * <p>
 * 临时资料用于“通用/智能问答”里的即时上下文增强：用户可以临时上传 PDF、Word、
 * Markdown 等文件，系统抽取文本后随本次聊天请求发送给 LLM。
 * <p>
 * 它和 {@code learning_material} 表里的持久资料不同：
 * <ul>
 *   <li>不会进入资料管理列表，不生成长期资料记录；</li>
 *   <li>不会参与资料问答的全局长期向量库检索，但会在当前问答请求内按问题即时切片检索；</li>
 *   <li>历史记录只保存压缩后的预览文本和元数据，用于历史回显和再次预览。</li>
 * </ul>
 *
 * @param id           前端生成或后端返回的临时资料 ID，用于历史回显时区分附件
 * @param title        展示标题，通常来自文件名或用户输入标题
 * @param originalName 原始文件名
 * @param sourceType   文件类型，如 PDF、DOCX、PPT、TXT、MD、MULTI
 * @param text         已抽取的正文文本，会作为 LLM 的临时上下文
 * @param excerpt      摘要片段，用于卡片快速展示
 * @param fileSize     原文件大小，单位字节
 * @param parts        多文件临时资料的子资料列表
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatTemporaryMaterial(
    String id,
    String title,
    String originalName,
    String sourceType,
    String text,
    String excerpt,
    Long fileSize,
    List<ChatTemporaryMaterial> parts
) {
}
