package com.mytext.learningassistant.rerank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LocalHeuristicRerankerClient implements RerankerClient {

    private static final Pattern TERM_PATTERN = Pattern.compile("[\\p{IsHan}a-zA-Z0-9+#./-]{2,}");
    private static final Set<String> WEAK_TERMS = Set.of(
        "什么", "怎么", "为什么", "哪里", "哪个", "哪些", "一个", "介绍", "解释", "区别", "作用", "优点", "缺点", "优缺点",
        "what", "how", "why", "where", "which", "does", "this", "that", "with", "about"
    );

    private final RerankerProperties properties;

    public LocalHeuristicRerankerClient(RerankerProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<RerankedCandidate> rerank(String query, List<RerankCandidate> candidates) {
        if (!properties.enabled() || query == null || query.isBlank() || candidates == null || candidates.size() < 2) {
            return List.of();
        }
        List<RerankCandidate> limited = candidates.stream()
            .limit(properties.candidates())
            .toList();
        if (limited.size() < 2) {
            return List.of();
        }

        double maxRetrievalScore = limited.stream()
            .mapToDouble(candidate -> Math.max(0.0, candidate.retrievalScore()))
            .max()
            .orElse(0.0);
        List<String> terms = significantTerms(query);
        String normalizedQuery = normalize(query);

        return limited.stream()
            .map(candidate -> {
                double retrievalScore = maxRetrievalScore <= 0.0 ? 0.0 : Math.max(0.0, candidate.retrievalScore()) / maxRetrievalScore;
                double lexicalScore = lexicalScore(normalizedQuery, terms, candidate.text());
                double score = properties.retrievalWeight() * retrievalScore + properties.lexicalWeight() * lexicalScore;
                return new RerankedCandidate(candidate.id(), score);
            })
            .sorted(Comparator.comparingDouble(RerankedCandidate::score).reversed())
            .toList();
    }

    private List<String> significantTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TERM_PATTERN.matcher(query);
        while (matcher.find()) {
            String term = normalize(matcher.group());
            addSignificantTerm(terms, term);
            addSignificantTerm(terms, term.replaceAll("(的)?(优缺点|优点|缺点|特点|特征|优势|劣势|局限|风险)$", ""));
        }
        return new ArrayList<>(terms).stream().limit(8).toList();
    }

    private void addSignificantTerm(Set<String> terms, String term) {
        if (term.length() >= 2 && !WEAK_TERMS.contains(term)) {
            terms.add(term);
        }
    }

    private double lexicalScore(String normalizedQuery, List<String> terms, String text) {
        String normalizedText = normalize(text);
        if (normalizedText.isBlank()) {
            return 0.0;
        }

        double score = 0.0;
        if (!normalizedQuery.isBlank() && normalizedText.contains(normalizedQuery)) {
            score += 0.35;
        }
        if (!terms.isEmpty()) {
            long matches = terms.stream().filter(normalizedText::contains).count();
            score += 0.55 * ((double) matches / terms.size());
            int earliest = terms.stream()
                .mapToInt(normalizedText::indexOf)
                .filter(index -> index >= 0)
                .min()
                .orElse(-1);
            if (earliest >= 0) {
                score += Math.max(0.0, 0.10 - earliest / 8000.0);
            }
        }
        return Math.min(1.0, score);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", "")
            .replaceAll("[\\p{Punct}\\u3000-\\u303f\\uff00-\\uffef]", "");
    }
}
