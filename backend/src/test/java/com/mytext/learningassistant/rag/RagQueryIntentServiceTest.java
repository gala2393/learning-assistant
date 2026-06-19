package com.mytext.learningassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RagQueryIntentServiceTest {

    private final RagQueryIntentService service = new RagQueryIntentService();

    @Test
    void extractsDefinitionIntentFromChineseQuestion() {
        RagQueryIntentService.KeywordQuery query = service.extractKeywordQuery("什么是数据库索引？");

        assertThat(query).isNotNull();
        assertThat(query.intent()).isEqualTo(RagQueryIntentService.KeywordIntent.DEFINITION);
        assertThat(query.terms()).containsExactly("数据库索引");
    }

    @Test
    void extractsDefinitionIntentFromChineseSuffixQuestion() {
        RagQueryIntentService.KeywordQuery query = service.extractKeywordQuery("学分预警是什么？");

        assertThat(query).isNotNull();
        assertThat(query.intent()).isEqualTo(RagQueryIntentService.KeywordIntent.DEFINITION);
        assertThat(query.terms()).containsExactly("学分预警");
    }

    @Test
    void extractsComparisonIntentFromChineseQuestion() {
        RagQueryIntentService.KeywordQuery query = service.extractKeywordQuery("BM25 和向量检索有什么区别？");

        assertThat(query).isNotNull();
        assertThat(query.intent()).isEqualTo(RagQueryIntentService.KeywordIntent.COMPARISON);
        assertThat(query.terms()).containsExactly("BM25", "向量检索");
    }

    @Test
    void extractsEnglishHowDoesRetrievalQuestion() {
        RagQueryIntentService.KeywordQuery query = service.extractKeywordQuery("How does RAG retrieve relevant course chunks?");

        assertThat(query).isNotNull();
        assertThat(query.intent()).isEqualTo(RagQueryIntentService.KeywordIntent.FUNCTION);
        assertThat(query.terms()).containsExactly("RAG", "relevant course chunks");
    }

    @Test
    void filtersWeakTermsWhenExtractingSignificantTerms() {
        List<String> terms = service.significantQueryTerms("请介绍一下学生手册里的奖学金申请条件");

        assertThat(terms).contains("学生手册里的奖学金申请条件");
        assertThat(terms).doesNotContain("介绍");
    }

    @Test
    void scoresKeywordTextByIntentSignals() {
        List<String> terms = List.of(service.normalizeForTermMatch("索引"));

        double definitionScore = service.keywordScore("索引的定义：索引是用于加快查询的数据结构。", terms, RagQueryIntentService.KeywordIntent.DEFINITION);
        double plainScore = service.keywordScore("索引可以出现在很多资料里。", terms, RagQueryIntentService.KeywordIntent.DEFINITION);

        assertThat(definitionScore).isGreaterThan(plainScore);
    }
}
