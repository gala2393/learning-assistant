package com.mytext.learningassistant.rerank;

import java.util.List;

/**
 * 重排序客户端接口。
 * <p>
 * 定义了文档重排序的统一抽象，用于在检索阶段之后对候选文档进行二次排序，
 * 以提升最终返回给用户的文档与查询之间的相关性。
 * <p>
 * 目前有两个实现：
 * <ul>
 *   <li>{@link LocalHeuristicRerankerClient} —— 基于本地启发式算法（词法匹配 + 检索得分加权）</li>
 *   <li>{@link ExternalApiRerankerClient} —— 调用外部重排序 API（如 Cohere、BGE Reranker），失败时回退到本地实现</li>
 * </ul>
 */
public interface RerankerClient {

    /**
     * 对候选文档列表进行重排序。
     *
     * @param query      用户查询文本
     * @param candidates 待重排序的候选文档列表（来自检索阶段）
     * @return 重排序后的候选列表，按相关性得分从高到低排列；
     *         如果无法进行重排序（如参数无效、功能未启用等），则返回空列表
     */
    List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates);
}
