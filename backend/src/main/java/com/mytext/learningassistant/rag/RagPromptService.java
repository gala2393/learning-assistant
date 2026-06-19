package com.mytext.learningassistant.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mytext.learningassistant.material.LearningMaterialEntity;
import com.mytext.learningassistant.material.MaterialChunkEntity;

import org.springframework.stereotype.Service;

/**
 * RAG 回答提示与后处理服务。
 *
 * <p>第一阶段迁移回答装饰、证据不足提示和 fallback 回答。文本清洗、来源位置、摘录等工具暂时通过回调传入，
 * 后续再把这些通用文本工具继续收拢，避免一次性大改 prompt 与检索链路。</p>
 */
@Service
class RagPromptService {

    private static final int MAX_SELECTED_TEXT_CONTEXT_CHARS = 8_000;

    /**
     * 将检索出的资料片段组装成模型可读上下文。
     *
     * <p>页面/片段的选择仍由检索层完成；这里仅负责把已选片段转成 prompt 文本，
     * 避免 RagService 同时承担检索、prompt 和回答后处理三类职责。</p>
     */
    List<String> buildExcerpts(ChatRequest request, List<ScoredChunk> selectedChunks, PromptTextTools tools) {
        if (request.selectedText() != null && !request.selectedText().isBlank()) {
            return List.of("[用户选中内容]\n原文：" + truncate(request.selectedText(), MAX_SELECTED_TEXT_CONTEXT_CHARS));
        }
        if (request.chunkId() == null) {
            return selectedChunks.stream()
                .map(chunk -> sourceContext(chunk, "[资料来源]", tools))
                .toList();
        }
        List<String> excerpts = new ArrayList<>();
        for (int i = 0; i < selectedChunks.size(); i++) {
            String label = i == 0 ? "[当前阅读位置，优先依据]" : "[同页或同书补充依据]";
            excerpts.add(sourceContext(selectedChunks.get(i), label, tools));
        }
        return excerpts;
    }

    private String sourceContext(ScoredChunk scoredChunk, String label, PromptTextTools tools) {
        MaterialChunkEntity materialChunk = scoredChunk.chunk();
        LearningMaterialEntity material = scoredChunk.material();
        String location = tools.sourceLocation().apply(material, materialChunk);
        String section = materialChunk.getSectionTitle() == null || materialChunk.getSectionTitle().isBlank()
            ? "未命名章节"
            : materialChunk.getSectionTitle();
        String text = tools.contextExcerpt().apply(materialChunk.getChunkText());
        return label + "《" + material.getTitle() + "》/" + location + "/" + section
            + "\n[片段内容]\n原文：" + text;
    }

    String buildCleanAnswer(
        String question,
        List<ScoredChunk> topChunks,
        String answerStyle,
        EvidenceStatus evidenceStatus,
        PromptTextTools tools
    ) {
        if (evidenceStatus.blocksMaterialAnswer()) {
            return noEvidenceAnswer(question);
        }
        if (topChunks.isEmpty()) {
            return "当前资料未覆盖这个问题。你可以补充对应章节、上传相关资料，或者把问题改得更贴近课程标题、章节名和关键词。";
        }
        if (isHomeworkStyle(answerStyle)) {
            return buildHomeworkFallbackAnswer(question, topChunks, tools);
        }
        String evidence = topChunks.stream()
            .map(chunk -> {
                MaterialChunkEntity materialChunk = chunk.chunk();
                LearningMaterialEntity material = chunk.material();
                String location = tools.sourceLocation().apply(material, materialChunk);
                return "• 《" + material.getTitle() + "》" + location + "：" + tools.scoredExcerpt().apply(chunk);
            })
            .collect(Collectors.joining("\n"));
        return "可以从这些资料片段入手回答“" + question + "”：\n\n" + evidence;
    }

    String buildHomeworkFallbackAnswer(String question, List<ScoredChunk> topChunks, PromptTextTools tools) {
        String mainEvidence = topChunks.stream()
            .limit(3)
            .map(chunk -> tools.scoredExcerpt().apply(chunk))
            .filter(text -> !text.isBlank())
            .collect(Collectors.joining("；"));
        String references = topChunks.stream()
            .map(chunk -> {
                MaterialChunkEntity materialChunk = chunk.chunk();
                LearningMaterialEntity material = chunk.material();
                String location = tools.sourceLocation().apply(material, materialChunk);
                return "原文：《" + material.getTitle() + "》" + location + "：" + tools.scoredExcerpt().apply(chunk);
            })
            .distinct()
            .collect(Collectors.joining("\n"));
        return "围绕“" + question + "”，可以这样回答：" + mainEvidence
            + "。\n\n"
            + "原文依据：\n" + references;
    }

    String decorateAnswer(
        ChatRequest request,
        String content,
        List<ScoredChunk> selectedChunks,
        EvidenceStatus evidenceStatus,
        PromptTextTools tools
    ) {
        String question = request.question();
        String answerStyle = request.answerStyle();
        if (request.selectedText() != null && !request.selectedText().isBlank()) {
            String selectedAnswer = tools.cleanAnswer().apply(content);
            return selectedAnswer.isBlank() ? "未生成有效回答。" : selectedAnswer;
        }
        if (evidenceStatus.blocksMaterialAnswer()) {
            return noEvidenceAnswer(question);
        }
        if (selectedChunks.isEmpty() && !tools.isCasualQuestion().apply(question)) {
            return noEvidenceAnswer(question);
        }
        String answer = tools.cleanAnswer().apply(content);
        if (answer.isBlank()) {
            answer = "未生成有效回答。";
        }
        if (selectedChunks.isEmpty()) {
            if (tools.isCasualQuestion().apply(question)) {
                String casualAnswer = tools.removeNoSourceNotice().apply(answer);
                return casualAnswer.isBlank() ? tools.casualFallbackAnswer().apply(question) : casualAnswer;
            }
            String prefix = "当前资料未检索到足够页码依据，本次回答按通用问答给出。";
            if (answer.contains("当前资料未检索到足够页码") || answer.contains("资料中未检索到相关页码")) {
                return answer;
            }
            return prefix + "\n\n" + answer;
        }

        if (answer.contains("书本依据") || answer.contains("原文依据") || answer.contains("资料依据")) {
            return answer;
        }
        // 来源展示统一交给结构化 sources/retrievalDebug，避免回答正文再拼一套“原文依据”
        // 导致正文依据和下方来源卡片不是同一批片段。
        return answer;
    }

    List<String> withEvidenceStatusInstruction(List<String> excerpts, EvidenceStatus evidenceStatus) {
        if (!evidenceStatus.blocksMaterialAnswer()) {
            return excerpts;
        }
        java.util.ArrayList<String> guardedExcerpts = new java.util.ArrayList<>(excerpts.size() + 1);
        guardedExcerpts.add("[资料检索状态]\n"
            + "系统没有在当前资料中找到能够可靠支撑回答用户问题的片段。"
            + "下面如有片段，也只是低匹配度的检索诊断结果，不能当作资料依据。"
            + "请明确告知用户：当前资料里没有找到可靠依据，不能用通用知识冒充资料内容。");
        guardedExcerpts.addAll(excerpts);
        return guardedExcerpts;
    }

    String withEvidenceQuestionInstruction(String question, EvidenceStatus evidenceStatus) {
        if (!evidenceStatus.blocksMaterialAnswer()) {
            return question;
        }
        return question + "\n\n[资料依据约束]\n"
            + "当前资料检索最高匹配度约为 " + Math.round(evidenceStatus.topScore() * 100) + "%，低于可靠回答阈值。"
            + "问题关键词在资料片段中的覆盖率约为 " + Math.round(evidenceStatus.termCoverage() * 100) + "%。"
            + "请先明确说明：当前资料里没有找到可靠依据来回答这个问题。"
            + "然后可以在“通用知识补充”小节给出简洁正确答案，但必须声明这部分不是来自当前资料，不能冒充资料依据。";
    }

    String noEvidenceAnswer(String question) {
        return "当前资料里没有检索到足够依据来回答这个问题。\n\n"
            + "问题：" + (question == null ? "" : question.trim()) + "\n\n"
            + "可以补充更相关的章节、选中原文后提问，或换成资料中出现的关键词再试。";
    }

    String decorateGeneralAnswer(String content, PromptTextTools tools) {
        String answer = tools.cleanAnswer().apply(content);
        if (answer.isBlank()) {
            return "未生成有效回答。";
        }
        String cleaned = tools.removeNoSourceNotice().apply(answer);
        cleaned = cleaned
            .replaceAll("当前资料未覆盖[^。！？\\n]*[。！？\\n]*", "")
            .replaceAll("资料中未检索到[^。！？\\n]*[。！？\\n]*", "")
            .replaceAll("资料库中未命中[^。！？\\n]*[。！？\\n]*", "")
            .trim();
        if (cleaned.isBlank()) {
            return "抱歉，我暂时无法回答这个问题。请尝试换个方式提问。";
        }
        return cleaned;
    }

    String decorateLongDocumentAnswer(String content, PromptTextTools tools) {
        String answer = tools.cleanAnswer().apply(content);
        return answer.isBlank() ? "未生成有效回答。" : answer;
    }

    private boolean isHomeworkStyle(String answerStyle) {
        return "HOMEWORK".equalsIgnoreCase(String.valueOf(answerStyle).trim());
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "...";
    }

    record PromptTextTools(
        Function<String, String> cleanAnswer,
        Function<String, String> excerpt,
        Function<ScoredChunk, String> scoredExcerpt,
        Function<String, String> contextExcerpt,
        BiFunction<LearningMaterialEntity, MaterialChunkEntity, String> sourceLocation,
        Function<String, Boolean> isCasualQuestion,
        Function<String, String> removeNoSourceNotice,
        Function<String, String> casualFallbackAnswer
    ) {
    }
}
