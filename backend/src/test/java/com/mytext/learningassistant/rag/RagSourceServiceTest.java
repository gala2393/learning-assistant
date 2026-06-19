package com.mytext.learningassistant.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Locale;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

import org.junit.jupiter.api.Test;

class RagSourceServiceTest {

    private final RagRetrievalDebugService retrievalDebugService = new RagRetrievalDebugService();
    private final RagSourceService service = new RagSourceService(
        mock(RagQuestionSourceRepository.class),
        retrievalDebugService
    );

    @Test
    void groundedSourcesPreferBodyChunkThatActuallySupportsAnswer() {
        LearningMaterialEntity material = material(1L, "Structured SQL Material");
        ScoredChunk contentsChunk = scoredChunk(
            material,
            chunk(11L, 1, "Contents Chapter 2 SQL Query", "Contents"),
            0.98,
            "STRUCTURE_TOC"
        );
        ScoredChunk bodyChunk = scoredChunk(
            material,
            chunk(12L, 55, "BODY_SQL_JOIN_MARKER JOIN connects rows from multiple tables.", "Chapter 2 SQL Query"),
            0.62,
            "BM25"
        );

        List<ScoredChunk> grounded = service.groundSourcesToAnswer(
            "Chapter 2 explains that BODY_SQL_JOIN_MARKER JOIN connects rows from multiple tables.",
            List.of(contentsChunk, bodyChunk),
            "What does Chapter 2 say about JOIN?",
            textTools()
        );

        assertThat(grounded).hasSize(2);
        assertThat(grounded.get(0).chunk().getId()).isEqualTo(12L);
        assertThat(grounded.get(0).score()).isGreaterThan(grounded.get(1).score());

        try (RagRetrievalDebugService.RetrievalDebugSession ignored =
                 retrievalDebugService.begin("What does Chapter 2 say about JOIN?", material.getId(), false)) {
            List<RagSourceResponse> responses = service.toSourceResponses(
                grounded,
                new RagSourceService.SourceEvidence(false, List.of()),
                textTools()
            );

            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).chunkId()).isEqualTo(12L);
            assertThat(responses.get(0).excerpt()).contains("BODY_SQL_JOIN_MARKER");

            List<RetrievalDebugEntry> debugEntries = retrievalDebugService.snapshot();
            assertThat(debugEntries).isNotEmpty();
            assertThat(debugEntries.get(0).chunkId()).isEqualTo(12L);
            assertThat(debugEntries.get(0).excerpt()).contains("BODY_SQL_JOIN_MARKER");
            assertThat(debugEntries.get(0).selected()).isTrue();
            assertThat(debugEntries.get(0).selectedReason()).contains("最终进入回答来源响应");
        }
    }

    @Test
    void blockedEvidenceCapsDisplayedScoreToAvoidMisleadingPerfectMatch() {
        LearningMaterialEntity material = material(2L, "Weak Evidence Material");
        ScoredChunk weakChunk = scoredChunk(
            material,
            chunk(21L, 6, "目录 第六章 锁机制", "目录"),
            1.35,
            "STRUCTURE_TOC"
        );

        try (RagRetrievalDebugService.RetrievalDebugSession ignored =
                 retrievalDebugService.begin("锁等待超时怎么解决？", material.getId(), false)) {
            List<RagSourceResponse> responses = service.toSourceResponses(
                List.of(weakChunk),
                new RagSourceService.SourceEvidence(true, List.of("锁", "等待", "超时")),
                textTools()
            );

            assertThat(responses).hasSize(1);
            assertThat(responses.get(0).score()).isBetween(0.18, 0.58);
            assertThat(responses.get(0).score()).isLessThan(1.0);

            List<RetrievalDebugEntry> debugEntries = retrievalDebugService.snapshot();
            assertThat(debugEntries).hasSize(1);
            assertThat(debugEntries.get(0).selected()).isTrue();
            assertThat(debugEntries.get(0).finalScore()).isEqualTo(responses.get(0).score());
        }
    }

    @Test
    void mixedStructureQuestionDoesNotLetDirectoryChunkBeatRequestedBodySection() {
        LearningMaterialEntity material = material(3L, "Student Handbook");
        ScoredChunk contentsChunk = scoredChunk(
            material,
            chunk(31L, 6, "目录 第五部分 毕业与离校", "目录"),
            0.95,
            "STRUCTURE_TOC"
        );
        ScoredChunk bodyChunk = scoredChunk(
            material,
            chunk(32L, 55, "第五部分 毕业与离校 PART_FIVE_MARKER 档案转递和证书领取都在这一部分说明。", "第五部分 毕业与离校"),
            0.58,
            "HYBRID_FUSION"
        );

        List<ScoredChunk> grounded = service.groundSourcesToAnswer(
            "第五部分主要说明档案转递和证书领取。",
            List.of(contentsChunk, bodyChunk),
            "这份手册有哪些部分，并说明第五部分讲了什么，包括档案转递和证书领取？",
            textTools()
        );

        assertThat(grounded).hasSize(2);
        assertThat(grounded.get(0).chunk().getId()).isEqualTo(32L);
        assertThat(grounded.get(0).score()).isGreaterThan(grounded.get(1).score());
        assertThat(grounded.get(0).score()).isGreaterThan(0.8);
        assertThat(grounded.get(1).score()).isLessThanOrEqualTo(0.56);
    }

    @Test
    void structureHeadingChunkDoesNotBeatBodyChunkForDetailQuestion() {
        LearningMaterialEntity material = material(4L, "Section Heading Material");
        ScoredChunk headingChunk = scoredChunk(
            material,
            chunk(41L, 55, "Part 5 Graduation", "Part 5 Graduation"),
            0.91,
            "STRUCTURE_HEADING"
        );
        ScoredChunk bodyChunk = scoredChunk(
            material,
            chunk(
                42L,
                56,
                "Part 5 Graduation PART_FIVE_DETAIL_MARKER Graduation requirements, archive transfer, and certificate pickup are explained here.",
                "Part 5 Graduation"
            ),
            0.63,
            "HYBRID_FUSION"
        );

        List<ScoredChunk> grounded = service.groundSourcesToAnswer(
            "Part 5 covers PART_FIVE_DETAIL_MARKER graduation requirements, archive transfer, and certificate pickup.",
            List.of(headingChunk, bodyChunk),
            "What does Part 5 cover, including archive transfer and certificate pickup?",
            textTools()
        );

        assertThat(grounded).hasSize(2);
        assertThat(grounded.get(0).chunk().getId()).isEqualTo(42L);
        assertThat(grounded.get(0).score()).isGreaterThan(grounded.get(1).score());
        assertThat(grounded.get(1).score()).isLessThan(0.80);
    }

    @Test
    void pureContentsQuestionCanStillKeepDirectoryChunkWhenNoBodyEvidenceSupportsAnswer() {
        LearningMaterialEntity material = material(5L, "Contents Material");
        ScoredChunk contentsChunk = scoredChunk(
            material,
            chunk(51L, 6, "Contents Part 1 Enrollment Part 2 Courses Part 3 Exams", "Contents"),
            0.94,
            "STRUCTURE_TOC"
        );
        ScoredChunk bodyChunk = scoredChunk(
            material,
            chunk(52L, 55, "Part 3 Exams PART_THREE_MARKER Exam rules and grading details.", "Part 3 Exams"),
            0.70,
            "HYBRID_FUSION"
        );

        List<ScoredChunk> grounded = service.groundSourcesToAnswer(
            "This handbook contains Part 1 Enrollment, Part 2 Courses, and Part 3 Exams.",
            List.of(contentsChunk, bodyChunk),
            "List all parts in this handbook.",
            textTools()
        );

        assertThat(grounded)
            .extracting(chunk -> chunk.chunk().getId())
            .startsWith(51L);
        assertThat(grounded.get(0).score()).isGreaterThanOrEqualTo(0.86);
    }

    @Test
    void pureContentsQuestionCanKeepContentsPagesAheadWhenAnswerLacksBodyMarkers() {
        LearningMaterialEntity material = material(6L, "Handbook Contents");
        ScoredChunk contentsPage2 = scoredChunk(
            material,
            chunk(61L, 2, "Contents Part 1 Enrollment Part 2 Courses", "Contents"),
            0.96,
            "STRUCTURE_TOC"
        );
        ScoredChunk contentsPage3 = scoredChunk(
            material,
            chunk(62L, 3, "Contents Part 3 Exams Part 4 Awards", "Contents"),
            0.91,
            "STRUCTURE_TOC"
        );
        ScoredChunk bodyPage6 = scoredChunk(
            material,
            chunk(63L, 6, "Part 1 Enrollment BODY_ONE_MARKER registration and status confirmation.", "Part 1 Enrollment"),
            0.84,
            "HYBRID_FUSION"
        );
        ScoredChunk bodyPage7 = scoredChunk(
            material,
            chunk(64L, 7, "Part 2 Courses BODY_TWO_MARKER course selection and practice modules.", "Part 2 Courses"),
            0.82,
            "HYBRID_FUSION"
        );

        List<ScoredChunk> grounded = service.groundSourcesToAnswer(
            "This handbook contains Part 1 Enrollment, Part 2 Courses, Part 3 Exams, and Part 4 Awards.",
            List.of(contentsPage2, contentsPage3, bodyPage6, bodyPage7),
            "What parts are listed in this handbook? Please list them all.",
            textTools()
        );

        assertThat(grounded)
            .extracting(chunk -> chunk.chunk().getId())
            .startsWith(61L, 62L);
    }

    @Test
    void structureListingAnswerPrefersBodyPagesThatActuallyContainReturnedParts() {
        LearningMaterialEntity material = material(7L, "Student Handbook");
        ScoredChunk contentsPage = scoredChunk(
            material,
            chunk(71L, 2, "目录 第一部分 入学与注册 第二部分 课程学习 第三部分 考试与成绩 第四部分 奖助与处分 第五部分 毕业与离校", "目录"),
            0.96,
            "STRUCTURE_TOC"
        );
        ScoredChunk partFourBodyPage = scoredChunk(
            material,
            chunk(72L, 54, "第四部分 奖助与处分 PART_FOUR_MARKER 奖学金、助学金、违纪处分和申诉都在这一部分说明。", "第四部分 奖助与处分"),
            0.72,
            "HYBRID_FUSION"
        );
        ScoredChunk partFiveBodyPage = scoredChunk(
            material,
            chunk(73L, 55, "第五部分 毕业与离校 PART_FIVE_MARKER 毕业资格、离校手续、档案转递和证书领取都在这一部分说明。", "第五部分 毕业与离校"),
            0.74,
            "HYBRID_FUSION"
        );

        List<ScoredChunk> grounded = service.groundSourcesToAnswer(
            "这份学生手册共五个部分，其中第四部分是奖助与处分，第五部分是毕业与离校，包含 PART_FOUR_MARKER 和 PART_FIVE_MARKER 对应内容。",
            List.of(contentsPage, partFourBodyPage, partFiveBodyPage),
            "这份学生手册有哪些部分？",
            textTools()
        );

        assertThat(grounded)
            .extracting(chunk -> chunk.chunk().getId())
            .startsWith(73L, 72L);
        assertThat(grounded)
            .extracting(chunk -> chunk.chunk().getId())
            .contains(71L);
    }

    private RagSourceService.SourceTextTools textTools() {
        RagQueryIntentService queryIntentService = new RagQueryIntentService();
        return new RagSourceService.SourceTextTools(
            queryIntentService::normalizeForTermMatch,
            MaterialChunkEntity::getChunkText,
            chunk -> chunk.chunk().getChunkText(),
            queryIntentService::significantQueryTerms,
            (text, terms) -> {
                if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
                    return 0.0;
                }
                String normalizedText = queryIntentService.normalizeForTermMatch(text);
                long matched = terms.stream()
                    .map(queryIntentService::normalizeForTermMatch)
                    .filter(term -> !term.isBlank() && normalizedText.contains(term))
                    .distinct()
                    .count();
                return matched / (double) terms.size();
            }
        );
    }

    private LearningMaterialEntity material(Long id, String title) {
        LearningMaterialEntity material = new LearningMaterialEntity();
        material.setId(id);
        material.setTitle(title);
        return material;
    }

    private MaterialChunkEntity chunk(Long id, Integer pageNo, String chunkText, String sectionTitle) {
        MaterialChunkEntity chunk = new MaterialChunkEntity();
        chunk.setId(id);
        chunk.setPageNo(pageNo);
        chunk.setChunkIndex(id.intValue());
        chunk.setChunkText(chunkText);
        chunk.setSectionTitle(sectionTitle);
        return chunk;
    }

    private ScoredChunk scoredChunk(
        LearningMaterialEntity material,
        MaterialChunkEntity chunk,
        double score,
        String route
    ) {
        return new ScoredChunk(material, chunk, score, List.of())
            .withDebug(route, score, null, score, null)
            .withHighlightTerms(List.of(route.toLowerCase(Locale.ROOT)));
    }
}
