package com.mytext.learningassistant.rag;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 评估套件定时调度请求记录 —— 用于配置评估套件的定时执行计划。
 *
 * @param scheduled    是否启用定时执行
 * @param intervalHours 定时执行间隔（小时），范围 [1, 720]（即 1 小时到 30 天）
 */
public record RagEvaluationSuiteScheduleRequest(
    boolean scheduled,
    @Min(1)
    @Max(24 * 30)
    Integer intervalHours
) {
}
