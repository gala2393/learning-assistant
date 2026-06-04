package com.mytext.learningassistant.rerank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于本地启发式算法的重排序客户端实现。
 * <p>
 * 该类不依赖外部 API，而是在本地通过词法匹配的方式对候选文档进行重排序。
 * 排序得分由两部分加权组成：
 * <ul>
 *   <li>检索阶段的原始得分（归一化后）</li>
 *   <li>词法匹配得分（基于查询关键术语与文档文本的匹配程度）</li>
 * </ul>
 * 适合作为外部 API 不可用时的降级方案，或在不需要外部重排序服务时直接使用。
 */
public class LocalHeuristicRerankerClient implements RerankerClient {

    /**
     * 术语提取正则表达式。
     * 匹配连续的中文汉字、英文字母、数字及少量特殊字符（+#./-），
     * 最少长度为 2，用于从查询中提取有意义的关键词。
     */
    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{IsHan}a-zA-Z0-9+#./-]{2,}");

    /**
     * 弱意义术语集合。
     * 这些词语在查询中太常见、太泛化，对区分文档相关性帮助不大，
     * 因此在计算词法匹配得分时会被过滤掉。
     */
    private static final Set<String> WEAK_TERMS = Set.of(
        "什么", "怎么", "为什么", "哪里", "哪个", "哪些", "一个", "介绍", "解释", "区别", "作用", "优点", "缺点", "优缺点",
        "what", "how", "why", "where", "which", "does", "this", "that", "with", "about"
    );

    /** 重排序配置属性，包含权重、候选数量上限等 */
    private final RerankerProperties properties;

    /**
     * 构造方法。
     *
     * @param properties 重排序配置属性
     */
    public LocalHeuristicRerankerClient(RerankerProperties properties) {
        this.properties = properties;
    }

    /**
     * 使用本地启发式算法对候选文档进行重排序。
     * <p>
     * 执行逻辑：
     * <ol>
     *   <li>参数校验（功能是否启用、查询是否为空、候选数是否足够）</li>
     *   <li>将候选数限制到配置的最大值</li>
     *   <li>计算归一化检索得分：每个候选的检索得分除以最高检索得分</li>
     *   <li>提取查询中的关键术语</li>
     *   <li>对每个候选计算加权综合得分 = 检索权重 * 归一化检索得分 + 词法权重 * 词法匹配得分</li>
     *   <li>按综合得分从高到低排序返回</li>
     * </ol>
     *
     * @param query      用户查询文本
     * @param candidates 待重排序的候选文档列表
     * @return 重排序后的候选列表，按分数从高到低排列
     */
    @Override
    public List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates) {
        // 参数校验
        if (!properties.enabled() || query == null || query.isBlank() || candidates == null || candidates.size() < 2) {
            return List.of();
        }
        // 限制候选数
        List<RerankCandidate> limited = candidates.stream()
            .limit(properties.candidates())
            .toList();
        if (limited.size() < 2) {
            return List.of();
        }

        // 找到检索得分的最大值，用于后续归一化
        double maxRetrievalScore = limited.stream()
            .mapToDouble(candidate -> Math.max(0.0, candidate.retrievalScore()))
            .max()
            .orElse(0.0);
        // 从查询中提取关键术语
        List<String> terms = significantTerms(query);
        // 将查询文本规范化（小写、去空格、去标点）
        String normalizedQuery = normalize(query);

        // 对每个候选计算综合得分
        return limited.stream()
            .map(candidate -> {
                // 归一化检索得分：原始检索得分 / 最大检索得分，范围 [0, 1]
                double retrievalScore = maxRetrievalScore <= 0.0 ? 0.0 : Math.max(0.0, candidate.retrievalScore()) / maxRetrievalScore;
                // 词法匹配得分
                double lexicalScore = lexicalScore(normalizedQuery, terms, candidate.text());
                // 加权综合得分
                double score = properties.retrievalWeight() * retrievalScore + properties.lexicalWeight() * lexicalScore;
                return new RerankedCandidate(candidate.id(), score);
            })
            .sorted(Comparator.comparingDouble(RerankedCandidate::score).reversed())
            .toList();
    }

    /**
     * 从查询文本中提取有意义的关键词术语。
     * <p>
     * 提取过程：
     * <ul>
     *   <li>使用正则表达式匹配长度 >= 2 的中文/英文/数字序列</li>
     *   <li>过滤掉弱意义术语（如"什么"、"怎么"等）</li>
     *   <li>对包含"优点/缺点/优缺点"等后缀的术语，额外提取去掉后缀的词根</li>
     *   <li>最多保留 8 个术语，避免过多术语影响匹配计算</li>
     * </ul>
     *
     * @param query 查询文本
     * @return 提取的关键术语列表（已规范化为小写）
     */
    private List<String> significantTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TERM_PATTERN.matcher(query);
        while (matcher.find()) {
            String term = normalize(matcher.group());
            // 添加原始术语
            addSignificantTerm(terms, term);
            // 如果术语包含"优点/缺点"等后缀，同时添加去掉后缀的词根
            // 例如 "React的优缺点" 会同时提取 "React"
            addSignificantTerm(terms, term.replaceAll("(的)?(优缺点|优点|缺点|特点|特征|优势|劣势|局限|风险)$", ""));
        }
        // 最多保留 8 个术语
        return new ArrayList<>(terms).stream().limit(8).toList();
    }

    /**
     * 将术语添加到集合中（仅当术语长度 >= 2 且不在弱意义术语集合中时）。
     *
     * @param terms 术语集合
     * @param term  待添加的术语
     */
    private void addSignificantTerm(Set<String> terms, String term) {
        if (term.length() >= 2 && !WEAK_TERMS.contains(term)) {
            terms.add(term);
        }
    }

    /**
     * 计算查询与文档之间的词法匹配得分。
     * <p>
     * 得分由三部分组成：
     * <ul>
     *   <li>完整查询匹配（0.35 分）：如果文档包含完整查询文本，得 0.35 分</li>
     *   <li>术语覆盖率（最高 0.55 分）：匹配到的术语数占总术语数的比例 * 0.55</li>
     *   <li>位置加分（最高 0.10 分）：术语在文档中出现得越早，加分越多</li>
     * </ul>
     * 最终得分上限为 1.0。
     *
     * @param normalizedQuery 规范化后的查询文本
     * @param terms           查询中的关键术语列表
     * @param text            候选文档的文本内容
     * @return 词法匹配得分，范围 [0, 1]
     */
    private double lexicalScore(String normalizedQuery, List<String> terms, String text) {
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) {
            return 0.0;
        }

        double score = 0.0;
        // 完整查询匹配加分
        if (!normalizedQuery.isBlank() && normalizedText.contains(normalizedQuery)) {
            score += 0.35;
        }
        if (!terms.isEmpty()) {
            // 计算术语覆盖率：匹配到的术语数 / 总术语数
            long matches = terms.stream().filter(normalizedText::contains).count();
            score += 0.55 * ((double) matches / terms.size());
            // 位置加分：术语在文档中最早出现的位置越靠前，加分越多
            int earliest = terms.stream()
                .mapToInt(normalizedText::indexOf)
                .filter(index -> index >= 0)
                .min()
                .orElse(-1);
            if (earliest >= 0) {
                // 出现位置越早（索引越小），加分越大；最大 0.10 分
                score += Math.max(0.0, 0.10 - earliest / 8000.0);
            }
        }
        // 上限为 1.0
        return Math.min(1.0, score);
    }

    /**
     * 规范化文本：转小写、去除所有空白字符、去除标点符号和全角字符。
     * <p>
     * 这样可以使中英文文本在匹配时不因大小写、空格、标点等差异而失配。
     *
     * @param value 待规范化的文本
     * @return 规范化后的文本；如果输入为 null 则返回空字符串
     */
    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", "")       // 去除所有空白字符
            .replaceAll("[\\p{Punct}\\u3000-\\u303f\\uff00-\\uffef]", ""); // 去除标点符号和全角字符
    }
}
