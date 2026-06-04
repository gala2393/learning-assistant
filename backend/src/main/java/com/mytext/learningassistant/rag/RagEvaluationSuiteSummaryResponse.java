package com.mytext.learningassistant.rag;

/**
 * 评估套件摘要响应记录 —— 在套件列表页展示的简要信息。
 *
 * @param id                       套件 ID
 * @param name                     套件名称
 * @param description              套件描述
 * @param caseCount                套件中的用例数量
 * @param lastTotalCases           最近一次运行的总用例数
 * @param lastPassedCases          最近一次运行的通过数
 * @param lastPassRate             最近一次运行的通过率
 * @param lastAverageOverallScore  最近一次运行的平均综合得分
 * @param lastRunAt                最近一次运行的时间
 * @param scheduled                是否设置了定时执行
 * @param scheduleIntervalHours    定时间隔（小时）
 * @param nextRunAt                下次计划执行时间
 * @param updatedAt                最后更新时间
 */
public record RagEvaluationSuiteSummaryResponse(
    Long id,
    String name,
    String description,
    int caseCount,
    Integer lastTotalCases,
    Integer lastPassedCases,
    Double lastPassRate,
    Double lastAverageOverallScore,
    String lastRunAt,
    boolean scheduled,
    int scheduleIntervalHours,
    String nextRunAt,
    String updatedAt
) {
}
