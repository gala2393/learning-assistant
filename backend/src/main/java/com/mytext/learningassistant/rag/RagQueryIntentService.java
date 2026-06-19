package com.mytext.learningassistant.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * RAG 问题意图和关键词解析服务。
 *
 * <p>这个服务只负责把用户问题转换成检索可用的结构化信号：
 * 关键词、意图类型、归一化文本、关键词覆盖率和关键词直达评分。
 * 这些逻辑原本散落在 {@link RagService} 中，抽出来后可以让检索链路更容易解释和测试。</p>
 */
@Service
public class RagQueryIntentService {

    private static final Pattern TERM_DEFINITION_PATTERN = Pattern.compile(
        "(?i)(?:什么是|何为|解释一下|解释|定义|含义|概念|define|definition of|what is|what are)\\s*[\"“‘']?([^\"“”‘’？?，,。；;：:\\n]{2,80})[\"”’']?"
    );

    /**
     * 从问题中提取最重要的检索关键词。
     *
     * <p>优先使用结构化意图解析结果；如果无法识别固定意图，则退回到通用词元提取。
     * 返回值会经过归一化、去重和弱词过滤，最多保留 6 个关键词。</p>
     */
    public List<String> significantQueryTerms(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        KeywordQuery keywordQuery = extractKeywordQuery(question);
        if (keywordQuery != null && !keywordQuery.terms().isEmpty()) {
            return keywordQuery.terms().stream()
                .map(this::normalizeForTermMatch)
                .filter(term -> term.length() >= 2)
                .distinct()
                .toList();
        }
        List<String> terms = new ArrayList<>();
        Matcher matcher = Pattern.compile("[\\p{IsHan}a-zA-Z0-9+#./-]{2,}").matcher(question);
        while (matcher.find()) {
            String term = cleanKeywordTerm(matcher.group());
            if (term == null) {
                continue;
            }
            String normalized = normalizeForTermMatch(term);
            if (normalized.length() >= 2 && !isWeakQueryTerm(normalized)) {
                terms.add(normalized);
            }
        }
        return terms.stream().distinct().limit(6).toList();
    }

    /**
     * 识别用户问题的关键词意图。
     *
     * <p>目前覆盖定义、对比、出现位置、功能作用、特征方面和开放介绍类问题。
     * 返回 null 表示没有识别出足够稳定的关键词意图。</p>
     */
    public KeywordQuery extractKeywordQuery(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String trimmed = question.trim();
        KeywordQuery cnDefinition = extractChineseDefinitionQuery(trimmed);
        if (cnDefinition != null) {
            return cnDefinition;
        }
        KeywordQuery comparison = extractComparisonQuery(trimmed);
        if (comparison != null) {
            return comparison;
        }
        KeywordQuery occurrence = extractOccurrenceQuery(trimmed);
        if (occurrence != null) {
            return occurrence;
        }
        KeywordQuery function = extractFunctionQuery(trimmed);
        if (function != null) {
            return function;
        }
        KeywordQuery aspect = extractAspectQuery(trimmed);
        if (aspect != null) {
            return aspect;
        }
        KeywordQuery openEnded = extractOpenEndedTopicQuery(trimmed);
        if (openEnded != null) {
            return openEnded;
        }
        String definitionTerm = extractDefinitionTerm(trimmed);
        if (definitionTerm != null) {
            return new KeywordQuery(List.of(definitionTerm), KeywordIntent.DEFINITION);
        }
        return null;
    }

    /**
     * 计算问题关键词在文本中的覆盖率。
     */
    public double queryTermCoverage(String text, List<String> queryTerms) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return 0.0;
        }
        String normalizedText = normalizeForTermMatch(text);
        long matches = queryTerms.stream().filter(normalizedText::contains).count();
        return (double) matches / queryTerms.size();
    }

    /**
     * 计算关键词直达召回的排序分。
     */
    public double keywordScore(String text, List<String> normalizedTerms, KeywordIntent intent) {
        String normalizedText = normalizeForTermMatch(text);
        double score = 0.0;
        for (String normalizedTerm : normalizedTerms) {
            int index = normalizedText.indexOf(normalizedTerm);
            if (index < 0) {
                continue;
            }
            score += 20.0;
            score += Math.max(0.0, 5.0 - index / 80.0);
        }
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (intent == KeywordIntent.DEFINITION && containsAny(lower, "refers to", "defined as", "definition", "means", "定义", "概念", "含义", "是指", "指的是")) {
            score += 10.0;
        } else if (intent == KeywordIntent.FUNCTION && containsAny(lower, "used for", "used to", "function", "purpose", "role", "helps", "allows", "enables", "作用", "用途", "用于", "用来", "帮助")) {
            score += 10.0;
        } else if (intent == KeywordIntent.COMPARISON) {
            if (normalizedTerms.stream().allMatch(normalizedText::contains)) {
                score += 12.0;
            }
            if (containsAny(lower, "difference", "different", "similar", "relationship", "compared", "versus", "区别", "不同", "相同", "关系", "相关")) {
                score += 10.0;
            }
        } else if (intent == KeywordIntent.OCCURRENCE) {
            score += 4.0;
        }
        return score;
    }

    /**
     * 判断文本是否包含任意归一化后的关键词。
     */
    public boolean containsAnyTerm(String text, List<String> normalizedTerms) {
        String normalizedText = normalizeForTermMatch(text);
        return normalizedTerms != null && normalizedTerms.stream().anyMatch(normalizedText::contains);
    }

    /**
     * 判断文本是否包含指定归一化关键词。
     */
    public boolean containsTerm(String text, String normalizedTerm) {
        return normalizeForTermMatch(text).contains(normalizedTerm);
    }

    /**
     * 计算定义类匹配分。
     */
    public double definitionScore(String text, String normalizedTerm) {
        String normalizedText = normalizeForTermMatch(text);
        int index = normalizedText.indexOf(normalizedTerm);
        double score = index < 0 ? 0.0 : 20.0;
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "定义", "概念", "含义", "是指", "指的是", "refers to", "defined as", "definition")) {
            score += 8.0;
        }
        if (index >= 0) {
            score += Math.max(0.0, 5.0 - index / 80.0);
        }
        return score;
    }

    /**
     * 从定义类问题中提取术语名称。
     */
    public String extractDefinitionTerm(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher matcher = TERM_DEFINITION_PATTERN.matcher(question.trim());
        if (!matcher.find()) {
            return null;
        }
        String term = matcher.group(1).trim()
            .replaceAll("^(一个|这个|该|所谓|资料里的|文中的|课件里的|书中的)\\s*", "")
            .replaceAll("\\s*(是什么|是啥|的定义|的含义|的概念|是什么意思|指什么|怎么理解)$", "")
            .trim();
        return term.isBlank() ? null : term;
    }

    /**
     * 清洗关键词中的弱前缀、弱后缀和标点。
     */
    public String cleanKeywordTerm(String value) {
        if (value == null) {
            return null;
        }
        String term = value.trim()
            .replaceAll("^[\"'`\\s]+|[\"'`\\s]+$", "")
            .replaceAll("(?i)^(the|a|an|this|current|material|document)\\s+", "")
            .replaceAll("^(资料里的|文中的|课件里的|书中的|这个|该|所谓)\\s*", "")
            .replaceAll("(?i)\\s+(definition|meaning|concept|role|function|purpose|use)$", "")
            .replaceAll("\\s*(是什么|是啥|的定义|的含义|的概念|的作用|的用途|的区别|的关系)$", "")
            .replaceAll("[?.!,;:]+$", "")
            .replaceAll("[？！。，；：]+$", "")
            .trim();
        return term.isBlank() ? null : term;
    }

    /**
     * 文本归一化：小写化，并去除空白和常见标点，便于中英文混合关键词匹配。
     */
    public String normalizeForTermMatch(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。！？；：“”‘’（）《》、]+", "");
    }

    public boolean isWeakQueryTerm(String term) {
        return Set.of(
            "什么", "怎么", "为什么", "哪里", "哪个", "哪些", "一个", "介绍", "解释", "区别",
            "what", "how", "why", "where", "which", "does", "this", "that", "with"
        ).contains(term);
    }

    private KeywordQuery extractChineseDefinitionQuery(String question) {
        Matcher prefix = Pattern.compile("(?:\\u4ec0\\u4e48\\u662f|\\u4f55\\u4e3a|\\u89e3\\u91ca\\u4e00\\u4e0b|\\u89e3\\u91ca|\\u5b9a\\u4e49)\\s*(.{2,80}?)(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (prefix.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, prefix.group(1));
        }
        Matcher suffix = Pattern.compile("(.{2,80}?)\\s*(?:\\u662f\\u4ec0\\u4e48|\\u662f\\u5565|\\u7684\\u5b9a\\u4e49|\\u7684\\u542b\\u4e49|\\u7684\\u6982\\u5ff5|\\u662f\\u4ec0\\u4e48\\u610f\\u601d|\\u6307\\u4ec0\\u4e48|\\u600e\\u4e48\\u7406\\u89e3)(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (suffix.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, suffix.group(1));
        }
        return null;
    }

    private KeywordQuery extractComparisonQuery(String question) {
        Matcher cnBetween = Pattern.compile("(.{2,60}?)\\s*(?:\\u548c|\\u4e0e)\\s*(.{2,60}?)\\s*(?:\\u6709\\u4ec0\\u4e48\\u533a\\u522b|\\u7684\\u533a\\u522b|\\u7684\\u5173\\u7cfb)").matcher(question);
        if (cnBetween.find()) {
            return keywordQuery(KeywordIntent.COMPARISON, cnBetween.group(1), cnBetween.group(2));
        }
        Matcher cnChoice = Pattern.compile("(.{2,60}?)\\s*(?:\\u548c|\\u4e0e)\\s*(.{2,60}?)\\s*(?:\\u54ea\\u4e2a\\u66f4\\u597d|\\u54ea\\u4e2a\\u597d|\\u5982\\u4f55\\u9009\\u62e9|\\u600e\\u4e48\\u9009)").matcher(question);
        if (cnChoice.find()) {
            return keywordQuery(KeywordIntent.COMPARISON, cnChoice.group(1), cnChoice.group(2));
        }
        Matcher between = Pattern.compile("(?i)difference\\s+between\\s+(.{2,60}?)\\s+and\\s+(.{2,60})(?:[?.!]|$)").matcher(question);
        if (between.find()) {
            return keywordQuery(KeywordIntent.COMPARISON, between.group(1), between.group(2));
        }
        Matcher versus = Pattern.compile("(?i)(.{2,60}?)\\s+(?:vs|versus)\\s+(.{2,60})(?:[?.!]|$)").matcher(question);
        if (versus.find()) {
            return keywordQuery(KeywordIntent.COMPARISON, versus.group(1), versus.group(2));
        }
        return null;
    }

    private KeywordQuery extractOccurrenceQuery(String question) {
        Matcher cnWhere = Pattern.compile("(.{2,80}?)\\s*(?:\\u5728\\u54ea\\u91cc\\u63d0\\u5230|\\u54ea\\u91cc\\u63d0\\u5230|\\u51fa\\u73b0\\u5728\\u54ea\\u91cc|\\u51fa\\u73b0\\u5728\\u54ea|\\u51fa\\u73b0\\u8fc7\\u54ea\\u4e9b)").matcher(question);
        if (cnWhere.find()) {
            return keywordQuery(KeywordIntent.OCCURRENCE, cnWhere.group(1));
        }
        Matcher mentioned = Pattern.compile("(?i)where\\s+(?:is|are)\\s+(.{2,80}?)\\s+mentioned").matcher(question);
        if (mentioned.find()) {
            return keywordQuery(KeywordIntent.OCCURRENCE, mentioned.group(1));
        }
        Matcher appear = Pattern.compile("(?i)where\\s+does\\s+(.{2,80}?)\\s+appear").matcher(question);
        if (appear.find()) {
            return keywordQuery(KeywordIntent.OCCURRENCE, appear.group(1));
        }
        return null;
    }

    private KeywordQuery extractFunctionQuery(String question) {
        Matcher cnFunction = Pattern.compile("(.{2,80}?)\\s*(?:\\u6709\\u4ec0\\u4e48\\u7528|\\u7684\\u4f5c\\u7528|\\u7684\\u7528\\u9014|\\u4e3b\\u8981\\u4f5c\\u7528|\\u7528\\u6765\\u505a\\u4ec0\\u4e48)").matcher(question);
        if (cnFunction.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, cnFunction.group(1));
        }
        Matcher usedFor = Pattern.compile("(?i)what\\s+(?:is|are)\\s+(.{2,80}?)\\s+(?:used\\s+for|for)(?:[?.!]|$)").matcher(question);
        if (usedFor.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, usedFor.group(1));
        }
        Matcher of = Pattern.compile("(?i)(?:role|function|purpose|use)\\s+of\\s+(.{2,80})(?:[?.!]|$)").matcher(question);
        if (of.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, of.group(1));
        }
        return null;
    }

    private KeywordQuery extractAspectQuery(String question) {
        Matcher cnAspect = Pattern.compile("(.{2,80}?)\\s*(?:\\u7684)?(?:\\u4f18\\u7f3a\\u70b9|\\u4f18\\u70b9|\\u7f3a\\u70b9|\\u7279\\u70b9|\\u7279\\u5f81|\\u4f18\\u52bf|\\u52a3\\u52bf|\\u5c40\\u9650|\\u98ce\\u9669)(?:\\u662f\\u4ec0\\u4e48|\\u6709\\u54ea\\u4e9b|\\u5982\\u4f55|\\u600e\\u4e48\\u6837|\\u5462)?(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (cnAspect.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, cnAspect.group(1));
        }
        Matcher englishAspect = Pattern.compile("(?i)(?:advantages|disadvantages|pros|cons|strengths|weaknesses|features|limitations|risks)\\s+of\\s+(.{2,80})(?:[?.!]|$)").matcher(question);
        if (englishAspect.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, englishAspect.group(1));
        }
        return null;
    }

    private KeywordQuery extractOpenEndedTopicQuery(String question) {
        Matcher introduce = Pattern.compile("(?:\\u4ecb\\u7ecd\\u4e00\\u4e0b|\\u8bb2\\u4e00\\u4e0b|\\u8bf4\\u4e00\\u4e0b|\\u6982\\u62ec\\u4e00\\u4e0b|\\u8bf4\\u660e\\u4e00\\u4e0b)\\s*(.{2,80}?)(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (introduce.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, introduce.group(1));
        }
        Matcher principle = Pattern.compile("(.{2,80}?)\\s*(?:\\u7684)?(?:\\u539f\\u7406|\\u673a\\u5236|\\u6d41\\u7a0b|\\u8fc7\\u7a0b)(?:\\u662f\\u4ec0\\u4e48|\\u600e\\u4e48|\\u5982\\u4f55|\\u6709\\u54ea\\u4e9b)?(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (principle.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, principle.group(1));
        }
        Matcher reason = Pattern.compile("(?:\\u4e3a\\u4ec0\\u4e48|\\u4e3a\\u4f55)\\s*(.{2,80}?)(?:[\\uff1f?\\u3002]|$)").matcher(question);
        if (reason.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, reason.group(1));
        }
        Matcher englishIntro = Pattern.compile("(?i)(?:explain|introduce|summari[sz]e|describe)\\s+(.{2,80})(?:[?.!]|$)").matcher(question);
        if (englishIntro.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, englishIntro.group(1));
        }
        Matcher englishAction = Pattern.compile("(?i)how\\s+(?:does|do)\\s+(.{2,40}?)\\s+(retrieve|retrieves|find|select|choose|answer|answers|use|uses|combine|generate)\\s+(.{2,80})(?:[?.!]|$)").matcher(question);
        if (englishAction.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, englishAction.group(1), englishAction.group(3));
        }
        Matcher englishHow = Pattern.compile("(?i)how\\s+(?:does|do|is|are)\\s+(.{2,80})(?:work|happen|used|done)(?:[?.!]|$)").matcher(question);
        if (englishHow.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, englishHow.group(1));
        }
        return null;
    }

    private KeywordQuery keywordQuery(KeywordIntent intent, String... rawTerms) {
        List<String> terms = new ArrayList<>();
        for (String rawTerm : rawTerms) {
            String term = cleanKeywordTerm(rawTerm);
            if (term != null && !term.isBlank()) {
                terms.add(term);
            }
        }
        terms = terms.stream().distinct().limit(2).toList();
        return terms.isEmpty() ? null : new KeywordQuery(terms, intent);
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) {
            return false;
        }
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    public enum KeywordIntent {
        DEFINITION,
        FUNCTION,
        COMPARISON,
        OCCURRENCE
    }

    public record KeywordQuery(List<String> terms, KeywordIntent intent) {
    }
}
