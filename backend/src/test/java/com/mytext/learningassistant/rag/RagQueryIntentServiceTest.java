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
    void trimsTrailingChineseCopulaFromFieldLookupTerms() {
        List<String> terms = service.significantQueryTerms("项目名称是");

        assertThat(terms).containsExactly("项目名称");
        assertThat(service.queryTermCoverage("项目名称 基于大模型与 RAG 的课程学习助手设计与实现", terms)).isEqualTo(1.0);
    }

    @Test
    void trimsTrailingChineseListQuestionFromFieldLookupTerms() {
        List<String> terms = service.significantQueryTerms("技术栈有哪些");

        assertThat(terms).containsExactly("技术栈");
        assertThat(service.queryTermCoverage("技术栈 后端 Spring Boot 3.5 Java 21", terms)).isEqualTo(1.0);
    }

    @Test
    void extractsListFieldLookupAsKeywordQuery() {
        RagQueryIntentService.KeywordQuery query = service.extractKeywordQuery("技术栈有哪些");

        assertThat(query).isNotNull();
        assertThat(query.intent()).isEqualTo(RagQueryIntentService.KeywordIntent.OCCURRENCE);
        assertThat(query.terms()).containsExactly("技术栈");
    }

    @Test
    void scoresKeywordTextByIntentSignals() {
        List<String> terms = List.of(service.normalizeForTermMatch("索引"));

        double definitionScore = service.keywordScore("索引的定义：索引是用于加快查询的数据结构。", terms, RagQueryIntentService.KeywordIntent.DEFINITION);
        double plainScore = service.keywordScore("索引可以出现在很多资料里。", terms, RagQueryIntentService.KeywordIntent.DEFINITION);

        assertThat(definitionScore).isGreaterThan(plainScore);
    }
}
