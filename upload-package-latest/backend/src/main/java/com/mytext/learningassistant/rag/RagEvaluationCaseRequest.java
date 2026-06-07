package com.mytext.learningassistant.rag;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * RAG 评估用例请求记录 —— 表示评估套件中的一个测试用例。
 *
 * <p>每个用例包含一个待测试的问题，以及可选的期望答案关键词和期望来源关键词。
 * 评估时会实际调用 RAG 问答流程，然后检查生成的回答和引用来源是否覆盖了期望的关键词。</p>
 *
 * @param question              待测试的问题（必填，不超过 2000 字）
 * @param materialId            限定检索范围的资料 ID（可选，为空则在用户所有资料中检索）
 * @param expectedAnswerTerms   期望回答中应包含的关键词列表（用于覆盖率评估）
 * @param expectedSourceTerms   期望引用来源中应包含的关键词列表（用于来源相关性评估）
 */
public record RagEvaluationCaseRequest(
    @NotBlank
    @Size(max = 2000)
    String question,
    Long materialId,
    List<String> expectedAnswerTerms,
    List<String> expectedSourceTerms
) {
}
