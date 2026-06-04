package com.mytext.learningassistant.rag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BM25 评分器 —— 基于 BM25 算法对文档片段（chunk）进行关键词检索打分。
 *
 * <p>BM25 是一种经典的信息检索算法，它通过统计词频（TF）和逆文档频率（IDF）来衡量
 * 查询词与文档之间的相关程度。在 RAG 流程中，BM25 作为<strong>关键词检索</strong>的
 * 核心组件，与向量检索互补，共同构成混合检索策略。</p>
 *
 * <h3>核心流程：</h3>
 * <ol>
 *   <li><strong>构建索引</strong>：遍历所有 chunk，计算每个 token 的文档频率（DF）和词频（TF）</li>
 *   <li><strong>分词</strong>：对文本进行分词，支持中英文，并进行 n-gram 子串切分（针对中文）</li>
 *   <li><strong>打分</strong>：对给定查询，按 BM25 公式计算每个 chunk 的相关性得分</li>
 * </ol>
 *
 * @see ScoredChunk 最终携带分数的 chunk 包装
 */
public class Bm25Scorer {

    /** BM25 参数 k1：控制词频饱和度，值越大，高频词的贡献越大 */
    private static final double K1 = 1.5;

    /** BM25 参数 b：控制文档长度归一化程度，0 表示不归一化，1 表示完全归一化 */
    private static final double B = 0.75;

    /** 英文停用词集合，在分词时会被过滤掉，避免干扰检索结果 */
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is", "it",
        "of", "on", "or", "that", "the", "this", "to", "what", "when", "where", "which", "why",
        "with", "does", "do", "did", "can", "could", "would", "should", "explain"
    );

    /** 文档频率表：记录每个 token 出现在多少个不同的 chunk 中 */
    private final Map<String, Integer> documentFrequency;

    /** chunk 总数 */
    private final int totalDocuments;

    /** 所有 chunk 的平均长度（以 token 数量衡量），用于 BM25 长度归一化 */
    private final double averageDocumentLength;

    /** 每个 chunk 的长度（token 数量） */
    private final Map<Long, Integer> chunkLengths;

    /** 每个 chunk 的词频表：token -> 出现次数 */
    private final Map<Long, Map<String, Integer>> chunkTermFrequencies;

    /**
     * 构造 BM25 评分器，对传入的 chunk 列表建立倒排索引。
     *
     * <p>构建过程：</p>
     * <ol>
     *   <li>遍历每个 chunk，对文本进行分词得到 token 集合</li>
     *   <li>统计每个 chunk 中各 token 的词频（TF）</li>
     *   <li>统计每个 token 出现在多少个 chunk 中（文档频率 DF）</li>
     *   <li>计算所有 chunk 的平均长度</li>
     * </ol>
     *
     * @param chunks 待建索引的 chunk 列表，每个 chunk 包含 id 和文本内容
     */
    public Bm25Scorer(List<ChunkData> chunks) {
        this.totalDocuments = chunks.size();
        this.documentFrequency = new HashMap<>();
        this.chunkLengths = new HashMap<>();
        this.chunkTermFrequencies = new HashMap<>();

        long totalLength = 0;
        for (ChunkData chunk : chunks) {
            // 用于记录当前 chunk 中出现过的唯一 token（用于计算文档频率）
            Set<String> uniqueTokens = new HashSet<>();
            // 当前 chunk 的词频表
            Map<String, Integer> tf = new HashMap<>();
            // 先去除文本中的图片标记，避免干扰关键词检索
            String cleaned = stripImageMarkers(chunk.text());
            // 对清理后的文本进行分词
            for (String token : tokenize(cleaned)) {
                // 统计每个 token 在当前 chunk 中的出现次数
                tf.merge(token, 1, Integer::sum);
                uniqueTokens.add(token);
            }
            // 存储当前 chunk 的词频表
            chunkTermFrequencies.put(chunk.id(), tf);
            // 计算并存储当前 chunk 的总 token 数
            chunkLengths.put(chunk.id(), tf.values().stream().mapToInt(Integer::intValue).sum());
            totalLength += chunkLengths.get(chunk.id());
            // 更新文档频率：每个唯一 token 的 DF 值加 1
            for (String token : uniqueTokens) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }
        // 计算平均文档长度，避免除零
        this.averageDocumentLength = totalDocuments == 0 ? 1.0 : (double) totalLength / totalDocuments;
    }

    /**
     * 计算给定查询 token 集合对指定 chunk 的 BM25 得分。
     *
     * <p>BM25 打分公式（对每个查询 token 累加）：</p>
     * <pre>
     * score += IDF(token) * TF_norm(token)
     *
     * 其中：
     *   IDF(token) = ln((N - n + 0.5) / (n + 0.5) + 1.0)
     *   TF_norm    = (tf * (k1 + 1)) / (tf + k1 * (1 - b + b * dl / avgdl))
     *
     *   N     = 总文档数
     *   n     = 包含该 token 的文档数
     *   tf    = 该 token 在当前 chunk 中的词频
     *   dl    = 当前 chunk 的长度
     *   avgdl = 所有 chunk 的平均长度
     * </pre>
     *
     * @param queryTokens 查询文本分词后的 token 集合
     * @param chunkId     目标 chunk 的 ID
     * @return BM25 得分，值越大表示越相关；如果 chunk 不存在则返回 0.0
     */
    public double score(Set<String> queryTokens, long chunkId) {
        // 获取目标 chunk 的词频表
        Map<String, Integer> tf = chunkTermFrequencies.get(chunkId);
        if (tf == null) {
            return 0.0;
        }
        // 获取文档长度
        int dl = chunkLengths.getOrDefault(chunkId, 0);
        double score = 0.0;
        // 对每个查询 token 累加 BM25 得分
        for (String queryToken : queryTokens) {
            // 获取该 token 在当前 chunk 中的词频
            int termFreq = tf.getOrDefault(queryToken, 0);
            if (termFreq == 0) {
                // 该 token 不在当前 chunk 中，跳过
                continue;
            }
            // 获取包含该 token 的文档数
            int n = documentFrequency.getOrDefault(queryToken, 0);
            // 计算逆文档频率（IDF）：出现该 token 的文档越少，IDF 值越大，区分度越高
            double idf = Math.log((totalDocuments - n + 0.5) / (n + 0.5) + 1.0);
            // 计算归一化词频（TF normalization）：考虑文档长度的影响
            double tfNorm = (termFreq * (K1 + 1.0))
                / (termFreq + K1 * (1.0 - B + B * dl / averageDocumentLength));
            // 累加该 token 对最终得分的贡献
            score += idf * tfNorm;
        }
        return score;
    }

    /**
     * 对输入文本进行分词，返回去重后的 token 集合。
     *
     * <p>分词策略：</p>
     * <ul>
     *   <li>将文本转为小写后，按非中文、非字母数字的字符进行切分</li>
     *   <li>过滤掉停用词（英文常见虚词）</li>
     *   <li>对中文部分额外生成 2~6 字的 n-gram 子串，提高中文检索的召回率</li>
     * </ul>
     *
     * @param text 待分词的文本
     * @return 去重后的 token 集合
     */
    public Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) {
            return tokens;
        }
        // 按非中文、非字母数字的分隔符切分，并转为小写
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9]+");
        for (String part : parts) {
            if (!part.isBlank() && !STOP_WORDS.contains(part)) {
                tokens.add(part);
                // 对包含中文的部分，生成 2~6 字的子串（n-gram），提高中文检索的覆盖
                addHanSubtokens(tokens, part);
            }
        }
        return tokens;
    }

    /**
     * 对包含中文字符的 token 生成 n-gram 子串。
     *
     * <p>例如，对于 "数据库索引" 会生成：数据、据库、库索、索引、数据库、据库索、
     * 库索引、数据库索、据库索引、数据库索引 等子串。这样即使用户的查询词只是
     * 片段文本的一部分，也能被检索到。</p>
     *
     * @param tokens 已有的 token 集合，新的子串会直接添加到此集合中
     * @param part   待处理的原始 token
     */
    private void addHanSubtokens(Set<String> tokens, String part) {
        // 提取出纯中文部分
        String hanOnly = part.replaceAll("[^\\p{IsHan}]", "");
        if (hanOnly.length() < 2) {
            return;
        }
        // 最长取 6 个字的子串，避免子串过长失去检索意义
        int maxLength = Math.min(6, hanOnly.length());
        // 滑动窗口生成所有长度为 2 到 maxLength 的连续子串
        for (int length = 2; length <= maxLength; length++) {
            for (int start = 0; start + length <= hanOnly.length(); start++) {
                tokens.add(hanOnly.substring(start, start + length));
            }
        }
    }

    /**
     * 去除文本中的图片标记（如 {@code [[material-image:xxx]]} 和 {@code [image ocr:...]}），
     * 避免这些非内容标记干扰 BM25 的关键词检索。
     *
     * @param text 包含图片标记的原始文本
     * @return 去除图片标记后的干净文本
     */
    private static String stripImageMarkers(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\[\\[material-image:[^\\]]+\\]\\]\\s*", "")
            .replaceAll("\\[\\image ocr:[^\\]]*\\]\\s*[^\\[]*", "");
    }

    /**
     * chunk 数据记录，用于构造 BM25 索引时传入 chunk 的 id 和文本内容。
     *
     * @param id   chunk 的唯一标识
     * @param text chunk 的文本内容
     */
    public record ChunkData(long id, String text) {
    }
}
