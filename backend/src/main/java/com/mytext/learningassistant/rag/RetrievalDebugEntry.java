package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * 单个检索候选片段的调试信息。
 *
 * <p>这个结构不参与回答生成，只用于解释 RAG 链路为什么召回、重排并最终选择某个片段。
 * 前端或管理员后台可以据此排查“来源为什么不准”“为什么只命中目录页”等问题。</p>
 *
 * @param materialId      候选片段所属资料 ID
 * @param chunkId         候选片段 ID
 * @param materialTitle   资料标题
 * @param pageNo          页码，非分页文档可能为空
 * @param chunkIndex      片段序号
 * @param routes          片段被哪些检索路径召回，例如 VECTOR、BM25、CURRENT_PAGE
 * @param rawScore        原始召回分，或召回阶段的最高分
 * @param rerankScore     Reranker 重排分；未重排时为空
 * @param finalScore      最终用于排序或展示的分数
 * @param selected        是否最终进入回答来源
 * @param reason          召回原因或排序阶段说明
 * @param selectedReason  最终入选来源列表的原因；未入选时为空
 * @param penaltyReason   降权原因，未降权时为空
 */
public record RetrievalDebugEntry(
    Long materialId,
    Long chunkId,
    String materialTitle,
    Integer pageNo,
    Integer chunkIndex,
    String excerpt,
    List<String> routes,
    Double rawScore,
    Double rerankScore,
    Double finalScore,
    boolean selected,
    String reason,
    String selectedReason,
    String penaltyReason
) {
}
