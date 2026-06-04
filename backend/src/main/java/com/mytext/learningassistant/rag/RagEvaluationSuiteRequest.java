package com.mytext.learningassistant.rag;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 一次性评估套件请求记录 —— 用于临时运行一组评估用例（不保存为套件）。
 *
 * <p>与 {@link RagEvaluationSuiteSaveRequest} 的区别：本请求仅运行评估并返回结果，
 * 不会在数据库中持久化为评估套件。适用于快速验证 RAG 系统质量的场景。</p>
 *
 * @param cases 测试用例列表（必填，至少 1 个，最多 25 个）
 */
public record RagEvaluationSuiteRequest(
    @Valid
    @NotEmpty
    @Size(max = 25)
    List<RagEvaluationCaseRequest> cases
) {
}
