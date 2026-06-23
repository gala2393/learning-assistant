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
 * <p>该服务把用户口语化问题转换为检索可用的结构化信号，避免 BM25、语义检索和证据覆盖率
 * 直接使用“关键词索引呢”“技术栈有哪些”这类完整口语句子。</p>
 */
@Service
public class RagQueryIntentService {

    private static final Pattern TERM_DEFINITION_PATTERN = Pattern.compile(
        "(?i)(?:什么是|何为|解释一下|解释|定义|含义|概念|define|definition of|what is|what are)\\s*[\"“”'‘’]?([^\"“”'‘’？?。；;：:\\n]{2,80})[\"“”'‘’]?"
    );

    /**
     * 生成检索查询列表。
     *
     * <p>清洗后的核心关键词放在原始问题前面，使 BM25 和向量检索都优先召回真正主题。</p>
     */
    public List<String> retrievalQueries(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        String original = question.trim();
        List<String> queries = new ArrayList<>();
        KeywordQuery keywordQuery = extractKeywordQuery(original);
        if (keywordQuery != null && !keywordQuery.terms().isEmpty()) {
            queries.add(String.join(" ", keywordQuery.terms()));
        }
        queries.add(original);
        return queries.stream()
            .map(String::trim)
            .filter(query -> !query.isBlank())
            .distinct()
            .toList();
    }

    /**
     * 从问题中提取最重要的检索关键词。
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
        KeywordQuery listField = extractListFieldQuery(trimmed);
        if (listField != null) {
            return listField;
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

    public boolean containsAnyTerm(String text, List<String> normalizedTerms) {
        String normalizedText = normalizeForTermMatch(text);
        return normalizedTerms != null && normalizedTerms.stream().anyMatch(normalizedText::contains);
    }

    public boolean containsTerm(String text, String normalizedTerm) {
        return normalizeForTermMatch(text).contains(normalizedTerm);
    }

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

    public String extractDefinitionTerm(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher matcher = TERM_DEFINITION_PATTERN.matcher(question.trim());
        if (!matcher.find()) {
            return null;
        }
        String term = matcher.group(1).trim()
            .replaceAll("^(一个|这个|请|所谓|资料里的|文中的|课件里的|书中的)\\s*", "")
            .replaceAll("\\s*(是什么|是啥|的定义|的含义|的概念|是什么意思|指什么|怎么理解)$", "")
            .trim();
        term = stripColloquialQuestionSuffix(term);
        return term.isBlank() ? null : term;
    }

    private String stripColloquialQuestionSuffix(String term) {
        if (term == null || term.isBlank()) {
            return "";
        }
        return term
            .replaceAll("\\s*(?:是什么情况|什么情况|怎么回事|咋回事|行不行|可以吗|呢|吗|吧|啊)$", "")
            .trim();
    }

    public String cleanKeywordTerm(String value) {
        if (value == null) {
            return null;
        }
        String term = value.trim()
            .replaceAll("^[\"'`\\s]+|[\"'`\\s]+$", "")
            .replaceAll("(?i)^(the|a|an|this|current|material|document)\\s+", "")
            .replaceAll("^(资料里的|文中的|课件里的|书中的|这个|请|所谓)\\s*", "")
            .replaceAll("(?i)\\s+(definition|meaning|concept|role|function|purpose|use)$", "")
            .replaceAll("\\s*(是什么|是啥|有哪些|有什么|都有啥|都有哪些|几种|包括哪些|包含哪些|列一下|列出|的定义|的含义|的概念|的作用|的用途|的区别|的关系)$", "")
            .replaceAll("\\s*(?:是|为|可|叫做)$", "")
            .replaceAll("[?.!,;:]+$", "")
            .replaceAll("[？！，。；：]+$", "")
            .trim();
        term = stripColloquialQuestionSuffix(term);
        return term.isBlank() ? null : term;
    }

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
        Matcher prefix = Pattern.compile("(?:什么是|何为|解释一下|解释|定义)\\s*(.{2,80}?)(?:[？?。]|$)").matcher(question);
        if (prefix.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, prefix.group(1));
        }
        Matcher suffix = Pattern.compile("(.{2,80}?)\\s*(?:是什么|是啥|的定义|的含义|的概念|是什么意思|指什么|怎么理解)(?:[？?。]|$)").matcher(question);
        if (suffix.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, suffix.group(1));
        }
        return null;
    }

    private KeywordQuery extractComparisonQuery(String question) {
        Matcher cnBetween = Pattern.compile("(.{2,60}?)\\s*(?:和|与)\\s*(.{2,60}?)\\s*(?:有什么区别|的区别|的关系)").matcher(question);
        if (cnBetween.find()) {
            return keywordQuery(KeywordIntent.COMPARISON, cnBetween.group(1), cnBetween.group(2));
        }
        Matcher cnChoice = Pattern.compile("(.{2,60}?)\\s*(?:和|与)\\s*(.{2,60}?)\\s*(?:哪个更好|哪个好|如何选择|怎么选)").matcher(question);
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
        Matcher cnWhere = Pattern.compile("(.{2,80}?)\\s*(?:在哪里提到|哪里提到|出现在哪里|出现在哪|出现过哪些)").matcher(question);
        if (cnWhere.find()) {
            return keywordQuery(KeywordIntent.OCCURRENCE, cnWhere.group(1));
        }
        Matcher cnColloquialLookup = Pattern.compile("(.{2,80}?)(?:呢|吗|吧|啊|是什么情况|什么情况|怎么回事|咋回事|行不行|可以吗)(?:[？?。！!]|$)").matcher(question);
        if (cnColloquialLookup.find()) {
            return keywordQuery(KeywordIntent.OCCURRENCE, cnColloquialLookup.group(1));
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

    private KeywordQuery extractListFieldQuery(String question) {
        Matcher listQuestion = Pattern.compile("(.{2,80}?)\\s*(?:有哪些|有什么|都有啥|都有哪些|几种|包括哪些|包含哪些|列一下|列出)(?:[？?。]|$)").matcher(question);
        if (listQuestion.find()) {
            return keywordQuery(KeywordIntent.OCCURRENCE, listQuestion.group(1));
        }
        return null;
    }

    private KeywordQuery extractFunctionQuery(String question) {
        Matcher cnFunction = Pattern.compile("(.{2,80}?)\\s*(?:有什么用|的作用|的用途|主要作用|用来做什么)").matcher(question);
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
        Matcher cnAspect = Pattern.compile("(.{2,80}?)\\s*(?:的)?(?:优缺点|优点|缺点|特点|特征|优势|劣势|局限|风险)(?:是什么|有哪些|如何|怎么样|呢)?(?:[？?。]|$)").matcher(question);
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
        Matcher introduce = Pattern.compile("(?:介绍一下|讲一下|说一下|概括一下|说明一下)\\s*(.{2,80}?)(?:[？?。]|$)").matcher(question);
        if (introduce.find()) {
            return keywordQuery(KeywordIntent.DEFINITION, introduce.group(1));
        }
        Matcher principle = Pattern.compile("(.{2,80}?)\\s*(?:的)?(?:原理|机制|流程|过程)(?:是什么|怎么|如何|有哪些)?(?:[？?。]|$)").matcher(question);
        if (principle.find()) {
            return keywordQuery(KeywordIntent.FUNCTION, principle.group(1));
        }
        Matcher reason = Pattern.compile("(?:为什么|为何)\\s*(.{2,80}?)(?:[？?。]|$)").matcher(question);
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
