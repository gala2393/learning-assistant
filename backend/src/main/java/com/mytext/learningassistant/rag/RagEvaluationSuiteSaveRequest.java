package com.mytext.learningassistant.rag;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 保存评估套件请求记录 —— 用于创建或更新一个持久化的评估套件。
 *
 * <p>与 {@link RagEvaluationSuiteRequest} 的区别：本请求会将套件信息保存到数据库，
 * 后续可以反复运行、配置定时执行等。</p>
 *
 * @param name        套件名称（必填，不超过 120 字）
 * @param description 套件描述（可选，不超过 1000 字）
 * @param cases       测试用例列表（必填，至少 1 个，最多 25 个）
 */
public record RagEvaluationSuiteSaveRequest(
    @NotBlank
    @Size(max = 120)
    String name,
    @Size(max = 1000)
    String description,
    @Valid
    @NotEmpty
    @Size(max = 25)
    List<RagEvaluationCaseRequest> cases
) {
}
