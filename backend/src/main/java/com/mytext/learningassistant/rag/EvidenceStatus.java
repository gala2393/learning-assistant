package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * 当前资料是否足以支撑本轮回答的内部判断结果。
 *
 * <p>这个类型只在 rag 包内部流转，用于 prompt 约束、来源展示分和回答装饰，不作为公开 API。</p>
 */
record EvidenceStatus(
    boolean blocksMaterialAnswer,
    double topScore,
    double termCoverage,
    List<String> queryTerms
) {
    static EvidenceStatus notApplicable() {
        return new EvidenceStatus(false, 1.0, 1.0, List.of());
    }
}
