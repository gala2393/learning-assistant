package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * RAG 评估用例响应记录 —— 表示单个评估用例的执行结果。
 *
 * <p>每个用例执行后会生成多项评分指标，包括忠实度、上下文相关性、
 * 期望关键词覆盖率等，最终给出通过/不通过的判定。</p>
 *
 * @param caseIndex              用例序号（从 1 开始）
 * @param questionId             生成的问答记录 ID
 * @param question               测试问题
 * @param faithfulnessScore      忠实度得分（回答是否忠于引用的资料）
 * @param contextRelevanceScore  上下文相关性得分（引用资料是否与问题相关）
 * @param overallScore           综合得分
 * @param expectedAnswerCoverage 期望答案关键词覆盖率
 * @param expectedSourceCoverage 期望来源关键词覆盖率
 * @param verdict                评估判定结果："PASS"、"WARN" 或 "FAIL"
 * @param passed                 是否通过（综合判定）
 * @param missingAnswerTerms     回答中未覆盖的期望关键词
 * @param missingSourceTerms     来源中未覆盖的期望关键词
 */
public record RagEvaluationCaseResponse(
    int caseIndex,
    Long questionId,
    String question,
    double faithfulnessScore,
    double contextRelevanceScore,
    double overallScore,
    double expectedAnswerCoverage,
    double expectedSourceCoverage,
    String verdict,
    boolean passed,
    List<String> missingAnswerTerms,
    List<String> missingSourceTerms
) {
}
