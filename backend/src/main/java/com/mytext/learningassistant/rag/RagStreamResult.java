package com.mytext.learningassistant.rag;

import java.util.List;

/**
 * RAG 流式问答的返回结果。
 * <p>
 * 在 SSE 流式回答完成后，封装本次问答的元数据信息，
 * 包含问答 ID、所属对话 ID、最终完整回答文本和引用来源列表。
 *
 * @param questionId     本次问答记录的 ID，可用于后续评估、反馈等操作
 * @param conversationId 本次问答所属的对话 ID，用于多轮对话的关联
 * @param answer         最终的完整回答文本（含装饰性后缀，如引用来源说明）
 * @param sources        引用的学习资料来源列表，每个来源包含资料名称、页码、摘录等
 * @param continuable    当前回答是否可以通过“继续”接着生成
 * @param continuationHint 可继续生成时展示给前端的提示语
 * @param retrievalDebug 检索调试链路，解释流式回答选择来源的过程
 */
public record RagStreamResult(
    Long questionId,
    Long conversationId,
    String answer,
    List<RagSourceResponse> sources,
    boolean continuable,
    String continuationHint,
    List<RetrievalDebugEntry> retrievalDebug
) {
    public RagStreamResult(
        Long questionId,
        Long conversationId,
        String answer,
        List<RagSourceResponse> sources,
        boolean continuable,
        String continuationHint
    ) {
        this(questionId, conversationId, answer, sources, continuable, continuationHint, List.of());
    }
}
