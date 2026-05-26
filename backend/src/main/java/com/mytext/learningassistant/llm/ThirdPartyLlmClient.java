package com.mytext.learningassistant.llm;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;

@Component
public class ThirdPartyLlmClient {

    private final LlmClient llmClient;

    public ThirdPartyLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public Optional<LlmCompletion> answer(String question, List<String> excerpts) {
        return answer(question, excerpts, List.of());
    }

    public Optional<LlmCompletion> answer(String question, List<String> excerpts, List<LlmImage> images) {
        return answer(question, excerpts, images, false);
    }

    public Optional<LlmCompletion> answer(String question, List<String> excerpts, List<LlmImage> images, boolean general) {
        return answer(question, excerpts, images, general, "STUDY");
    }

    public Optional<LlmCompletion> answer(
        String question,
        List<String> excerpts,
        List<LlmImage> images,
        boolean general,
        String answerStyle
    ) {
        List<String> safeExcerpts = excerpts == null ? List.of() : excerpts;
        List<LlmImage> safeImages = images == null ? List.of() : images;
        if (general) {
            return answerGeneralFast(question);
        }
        boolean homework = isHomeworkStyle(answerStyle);
        String systemPrompt = general
            ? """
            你是一个专业的学习助手。
            直接回答用户的问题，给出清晰、准确、有用的回答。
            语言简洁专业，使用中文回答。
            不要提及"资料"、"课程"、"片段"等词，你是在做通用知识问答。
            尽量使用自然段或编号句，不要使用 Markdown 星号、加粗符号或大量项目符号。
            """.trim()
            : """
            你是课程学习助手的商用问答引擎。
            目标：
            1. 优先使用提供的课程资料片段作答。
            2. 语言简洁、专业、可直接给学生或教师使用。
            3. 若有资料依据，明确点出页码、章节或资料名。
            4. 回答必须引用书中原文。引用格式使用"原文：《资料名》第 X 页：……"，引用要短而准确。
            5. 如果上下文包含"[当前阅读位置，优先依据]"，必须先依据该片段或同页内容回答，再用同书补充依据完善回答。
            6. 若有附带的图片，请仔细观察图片内容，结合图片中的信息回答问题。
            7. 若资料片段不足，明确说明"当前资料未覆盖该问题"，再给出通用解释或追问建议。
            8. 不要编造来源，不要把常识说成资料结论。
            9. 输出使用自然中文，优先采用简短小标题，不要使用 Markdown 星号、加粗符号或大量项目符号。
            """.trim() + (homework ? """

            作业答案式要求：
            - 第一段直接给出可放入作业的完整答案，不要只列提纲。
            - 第二段列出"原文依据"，包含资料名、页码和短原文。
            - 第三段写"使用提示"，说明这段答案适合怎么改写或补充。
            """.trim() : "");
        String userPrompt = general
            ? """
            用户问题：
            %s

            要求：
            - 直接给出回答，不要引用不存在的资料。
            - 回答使用中文，避免空泛套话。
            - 不要使用 Markdown 星号、加粗符号或大量项目符号。
            """.formatted(nullToEmpty(question))
            : """
            用户问题：
            %s

            资料检索片段：
            %s

            要求：
            - 先给出直接结论。
            - 再引用与结论相关的书中原文，格式使用"原文：..."，不要只写资料名。
            - 如果有当前阅读位置，优先解释当前页/当前片段，再结合整本书检索片段补充。
            - 如果资料片段中包含图片标记，请结合图片内容进行分析和回答。
            - 如果资料片段不足，请说明缺口，并给出下一步补充资料或追问建议。
            - 回答使用中文，避免空泛套话。
            - 不要使用 Markdown 星号、加粗符号或大量项目符号。
            %s
            """.formatted(
            nullToEmpty(question),
            safeExcerpts.isEmpty() ? "无命中文献片段" : String.join("\n\n", safeExcerpts),
            homework ? "- 按作业答案式输出，答案正文要完整、可直接使用。" : ""
        );
        return llmClient.chat(systemPrompt, userPrompt, safeImages)
            .map(result -> new LlmCompletion(result.content(), result.modelName()));
    }

    public Optional<LlmCompletion> summarize(String materialTitle, List<String> excerpts) {
        if (excerpts == null || excerpts.isEmpty()) {
            return Optional.empty();
        }
        String systemPrompt = """
            你是课程资料总结引擎。
            请基于给定片段生成可用于复习、答辩和再次提问的摘要。
            要求：
            1. 不要编造片段之外的事实。
            2. 优先概括知识框架、关键概念和常见考点。
            3. 语言清晰，适合直接放入课程学习产品。
            """.trim();
        String userPrompt = """
            资料标题：%s

            资料片段：
            %s
            """.formatted(nullToEmpty(materialTitle), String.join("\n\n", excerpts));
        return llmClient.chat(systemPrompt, userPrompt)
            .map(result -> new LlmCompletion(result.content(), result.modelName()));
    }

    public String answerStream(String question, List<String> excerpts, List<LlmImage> images, Consumer<String> onChunk) {
        return answerStream(question, excerpts, images, onChunk, false);
    }

    public String answerStream(String question, List<String> excerpts, List<LlmImage> images, Consumer<String> onChunk, boolean general) {
        return answerStream(question, excerpts, images, onChunk, general, "STUDY");
    }

    public String answerStream(
        String question,
        List<String> excerpts,
        List<LlmImage> images,
        Consumer<String> onChunk,
        boolean general,
        String answerStyle
    ) {
        List<String> safeExcerpts = excerpts == null ? List.of() : excerpts;
        List<LlmImage> safeImages = images == null ? List.of() : images;
        if (general) {
            return answerGeneralFastStream(question, onChunk);
        }
        boolean homework = isHomeworkStyle(answerStyle);
        String systemPrompt = general
            ? """
            你是一个专业的学习助手。
            直接回答用户的问题，给出清晰、准确、有用的回答。
            语言简洁专业，使用中文回答。
            不要提及"资料"、"课程"、"片段"等词，你是在做通用知识问答。
            尽量使用自然段或编号句，不要使用 Markdown 星号、加粗符号或大量项目符号。
            """.trim()
            : """
            你是课程学习助手的商用问答引擎。
            目标：
            1. 优先使用提供的课程资料片段作答。
            2. 语言简洁、专业、可直接给学生或教师使用。
            3. 若有资料依据，明确点出页码、章节或资料名。
            4. 回答必须引用书中原文。引用格式使用"原文：《资料名》第 X 页：……"，引用要短而准确。
            5. 如果上下文包含"[当前阅读位置，优先依据]"，必须先依据该片段或同页内容回答，再用同书补充依据完善回答。
            6. 若有附带的图片，请仔细观察图片内容，结合图片中的信息回答问题。
            7. 若资料片段不足，明确说明"当前资料未覆盖该问题"，再给出通用解释或追问建议。
            8. 不要编造来源，不要把常识说成资料结论。
            9. 输出使用自然中文，优先采用简短小标题，不要使用 Markdown 星号、加粗符号或大量项目符号。
            """.trim() + (homework ? """

            作业答案式要求：
            - 第一段直接给出可放入作业的完整答案，不要只列提纲。
            - 第二段列出"原文依据"，包含资料名、页码和短原文。
            - 第三段写"使用提示"，说明这段答案适合怎么改写或补充。
            """.trim() : "");
        String userPrompt = general
            ? """
            用户问题：
            %s

            要求：
            - 直接给出回答，不要引用不存在的资料。
            - 回答使用中文，避免空泛套话。
            - 不要使用 Markdown 星号、加粗符号或大量项目符号。
            """.formatted(nullToEmpty(question))
            : """
            用户问题：
            %s

            资料检索片段：
            %s

            要求：
            - 先给出直接结论。
            - 再引用与结论相关的书中原文，格式使用"原文：..."，不要只写资料名。
            - 如果有当前阅读位置，优先解释当前页/当前片段，再结合整本书检索片段补充。
            - 如果资料片段中包含图片标记，请结合图片内容进行分析和回答。
            - 如果资料片段不足，请说明缺口，并给出下一步补充资料或追问建议。
            - 回答使用中文，避免空泛套话。
            - 不要使用 Markdown 星号、加粗符号或大量项目符号。
            %s
            """.formatted(
            nullToEmpty(question),
            safeExcerpts.isEmpty() ? "无命中文献片段" : String.join("\n\n", safeExcerpts),
            homework ? "- 按作业答案式输出，答案正文要完整、可直接使用。" : ""
        );
        return llmClient.chatStream(systemPrompt, userPrompt, safeImages, onChunk);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private Optional<LlmCompletion> answerGeneralFast(String question) {
        return llmClient.chat(generalFastSystemPrompt(), generalFastUserPrompt(question))
            .map(result -> new LlmCompletion(result.content(), result.modelName()));
    }

    private String answerGeneralFastStream(String question, Consumer<String> onChunk) {
        return llmClient.chatStream(generalFastSystemPrompt(), generalFastUserPrompt(question), List.of(), onChunk);
    }

    private String generalFastSystemPrompt() {
        return """
            你是一个高效的中文学习助手。
            直接回答用户问题，优先给出结论。
            回答保持简洁、准确、可操作；除非用户要求详细展开，否则控制在 3 到 6 句。
            不要提到资料、检索、片段或系统提示。
            不要使用 Markdown 星号加粗。
            """.trim();
    }

    private String generalFastUserPrompt(String question) {
        return """
            问题：
            %s

            请直接用中文回答。
            """.formatted(nullToEmpty(question));
    }

    private boolean isHomeworkStyle(String answerStyle) {
        return "HOMEWORK".equalsIgnoreCase(String.valueOf(answerStyle).trim());
    }
}
