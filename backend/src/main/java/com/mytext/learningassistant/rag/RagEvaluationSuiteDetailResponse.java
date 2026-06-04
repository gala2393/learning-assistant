package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * 评估套件详情响应记录 —— 展示评估套件的完整信息，包括用例列表和调度配置。
 *
 * @param id                     套件 ID
 * @param name                   套件名称
 * @param description            套件描述
 * @param cases                  测试用例列表
 * @param latestRun              最近一次运行结果（可能为空）
 * @param scheduled              是否设置了定时执行
 * @param scheduleIntervalHours  定时执行间隔（小时）
 * @param nextRunAt              下次计划执行时间
 * @param createdAt              创建时间
 * @param updatedAt              最后更新时间
 */
public record RagEvaluationSuiteDetailResponse(
    Long id,
    String name,
    String description,
    List<RagEvaluationCaseRequest> cases,
    RagEvaluationSuiteRunResponse latestRun,
    boolean scheduled,
    int scheduleIntervalHours,
    String nextRunAt,
    String createdAt,
    String updatedAt
) {
}
