package com.mytext.learningassistant.rag;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class Bm25Scorer {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from", "how", "in", "is", "it",
        "of", "on", "or", "that", "the", "this", "to", "what", "when", "where", "which", "why",
        "with", "does", "do", "did", "can", "could", "would", "should", "explain"
    );

    private final Map<String, Integer> documentFrequency;
    private final int totalDocuments;
    private final double averageDocumentLength;
    private final Map<Long, Integer> chunkLengths;
    private final Map<Long, Map<String, Integer>> chunkTermFrequencies;

    public Bm25Scorer(List<ChunkData> chunks) {
        this.totalDocuments = chunks.size();
        this.documentFrequency = new HashMap<>();
        this.chunkLengths = new HashMap<>();
        this.chunkTermFrequencies = new HashMap<>();

        long totalLength = 0;
        for (ChunkData chunk : chunks) {
            Set<String> uniqueTokens = new HashSet<>();
            Map<String, Integer> tf = new HashMap<>();
            String cleaned = stripImageMarkers(chunk.text());
            for (String token : tokenize(cleaned)) {
                tf.merge(token, 1, Integer::sum);
                uniqueTokens.add(token);
            }
            chunkTermFrequencies.put(chunk.id(), tf);
            chunkLengths.put(chunk.id(), tf.values().stream().mapToInt(Integer::intValue).sum());
            totalLength += chunkLengths.get(chunk.id());
            for (String token : uniqueTokens) {
                documentFrequency.merge(token, 1, Integer::sum);
            }
        }
        this.averageDocumentLength = totalDocuments == 0 ? 1.0 : (double) totalLength / totalDocuments;
    }

    public double score(Set<String> queryTokens, long chunkId) {
        Map<String, Integer> tf = chunkTermFrequencies.get(chunkId);
        if (tf == null) {
            return 0.0;
        }
        int dl = chunkLengths.getOrDefault(chunkId, 0);
        double score = 0.0;
        for (String queryToken : queryTokens) {
            int termFreq = tf.getOrDefault(queryToken, 0);
            if (termFreq == 0) {
                continue;
            }
            int n = documentFrequency.getOrDefault(queryToken, 0);
            double idf = Math.log((totalDocuments - n + 0.5) / (n + 0.5) + 1.0);
            double tfNorm = (termFreq * (K1 + 1.0))
                / (termFreq + K1 * (1.0 - B + B * dl / averageDocumentLength));
            score += idf * tfNorm;
        }
        return score;
    }

    public Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null) {
            return tokens;
        }
        String[] parts = text.toLowerCase(Locale.ROOT).split("[^\\p{IsHan}a-z0-9]+");
        for (String part : parts) {
            if (!part.isBlank() && !STOP_WORDS.contains(part)) {
                tokens.add(part);
                addHanSubtokens(tokens, part);
            }
        }
        return tokens;
    }

    private void addHanSubtokens(Set<String> tokens, String part) {
        String hanOnly = part.replaceAll("[^\\p{IsHan}]", "");
        if (hanOnly.length() < 2) {
            return;
        }
        int maxLength = Math.min(6, hanOnly.length());
        for (int length = 2; length <= maxLength; length++) {
            for (int start = 0; start + length <= hanOnly.length(); start++) {
                tokens.add(hanOnly.substring(start, start + length));
            }
        }
    }

    private static String stripImageMarkers(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\[\\[material-image:[^\\]]+\\]\\]\\s*", "")
            .replaceAll("\\[image ocr:[^\\]]*\\]\\s*[^\\[]*", "");
    }

    public record ChunkData(long id, String text) {
    }
}
