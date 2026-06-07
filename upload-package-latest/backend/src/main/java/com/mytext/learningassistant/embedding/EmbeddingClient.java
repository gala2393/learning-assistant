package com.mytext.learningassistant.embedding;

import java.util.List;
import java.util.Optional;

/**
 * 向量嵌入客户端接口（Embedding Client）。
 *
 * <p>职责：将自然语言文本转换为数值向量（即"嵌入向量"）。
 * 向量是一组浮点数，能够表示文本的语义信息，
 * 方便后续进行相似度计算和向量检索。</p>
 *
 * <p>典型实现：调用 OpenAI 或其他兼容接口的 Embedding API。</p>
 */
public interface EmbeddingClient {

    /**
     * 将输入文本转换为嵌入向量。
     *
     * @param text 需要向量化的自然语言文本，不能为空或空白
     * @return 嵌入向量（Double 列表）；如果文本为空、服务未配置或调用失败，返回 {@code Optional.empty()}
     */
    Optional<List<Double>> embed(String text);

    default Optional<List<Double>> embedDocument(String text) {
        return embed(text);
    }

    default Optional<List<Double>> embedQuery(String text) {
        return embed(text);
    }
}
