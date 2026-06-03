package com.mytext.learningassistant.llm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public class ThirdPartyLlmClient {

    private final LlmClient llmClient;
    private final LlmProperties properties;
    private final UserLlmConfigRepository userLlmConfigRepository;

    public ThirdPartyLlmClient(LlmClient llmClient) {
        this(llmClient, new LlmProperties(false, "", "", "", "chat-completions", java.time.Duration.ofSeconds(20)), null);
    }

    @Autowired
    public ThirdPartyLlmClient(
        LlmClient llmClient,
        LlmProperties properties,
        UserLlmConfigRepository userLlmConfigRepository
    ) {
        this.llmClient = llmClient;
        this.properties = properties;
        this.userLlmConfigRepository = userLlmConfigRepository;
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
        return answer(null, question, excerpts, images, general, answerStyle);
    }

    public Optional<LlmCompletion> answer(
        Long userId,
        String question,
        List<String> excerpts,
        List<LlmImage> images,
        boolean general,
        String answerStyle
    ) {
        List<String> safeExcerpts = excerpts == null ? List.of() : excerpts;
        List<LlmImage> safeImages = images == null ? List.of() : images;
        if (isModelIdentityQuestion(question)) {
            return Optional.of(currentModelCompletion(userId));
        }
        if (general && safeImages.isEmpty()) {
            return answerGeneralFast(userId, question);
        }
        boolean homework = isHomeworkStyle(answerStyle);
        String systemPrompt = general
            ? """
            你是一个专业的学习助手。
            直接回答用户的问题，给出清晰、准确、完整、有教学价值的回答。
            语言自然专业，使用中文回答。
            不要提及"资料"、"课程"、"片段"等词，你是在做通用知识问答。
            根据问题复杂度展开：先给结论，再解释原理，必要时给生活化例子、对比、步骤或一句话总结。
            可以使用简短小标题、编号、分隔线和少量加粗，让答案像正式学习笔记一样清楚。
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
        systemPrompt = systemPrompt + modelIdentityInstruction(userId);
        String userPrompt = general
            ? """
            用户问题：
            %s

            要求：
            - 直接给出回答，不要引用不存在的资料。
            - 回答使用中文，避免空泛套话。
            - 如果问题涉及概念区分、原理、计算、学习方法或操作步骤，请适当展开，不要只回答 3 到 6 句。
            - 可以使用小标题、编号、分隔线和少量加粗来组织内容。
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
        ResolvedLlmClient resolved = resolveClient(userId);
        return resolved.client().chat(systemPrompt, userPrompt, safeImages)
            .map(result -> toCompletion(result, resolved.customModel()));
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

    public Optional<List<String>> expandQuery(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String systemPrompt = """
            你是 RAG 检索查询改写器。
            根据用户问题生成 2 到 4 条适合检索课程资料的查询变体。
            包含：
            1. 明确的关键词查询。
            2. 一条接近资料原文风格的假设性答案短句（HyDE）。
            只输出查询本身，每行一条，不要编号，不要解释，不要编造具体页码。
            """.trim();
        String userPrompt = """
            用户问题：
            %s
            """.formatted(nullToEmpty(question));
        return llmClient.chat(systemPrompt, userPrompt)
            .map(result -> parseQueryExpansions(result.content(), question))
            .filter(expansions -> !expansions.isEmpty());
    }

    public Optional<String> generateHydeAnswer(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        String systemPrompt = """
            你是 RAG HyDE 查询生成器。
            根据用户问题生成一段可能出现在课程资料中的假设性答案，用于向量检索。
            要求：
            1. 只写 1 到 3 句事实风格文本。
            2. 使用资料原文常见的定义、原理、步骤、作用等表达。
            3. 不要写"可能"、"根据资料"、"以下是"等解释性开头。
            4. 不要编造具体页码、人名、日期或不存在的引用。
            """.trim();
        String userPrompt = """
            用户问题：
            %s
            """.formatted(nullToEmpty(question));
        return llmClient.chat(systemPrompt, userPrompt)
            .map(LlmResult::content)
            .map(this::normalizeHydeAnswer)
            .filter(answer -> !answer.isBlank());
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
        return answerStream(null, question, excerpts, images, onChunk, general, answerStyle);
    }

    public String answerStream(
        Long userId,
        String question,
        List<String> excerpts,
        List<LlmImage> images,
        Consumer<String> onChunk,
        boolean general,
        String answerStyle
    ) {
        List<String> safeExcerpts = excerpts == null ? List.of() : excerpts;
        List<LlmImage> safeImages = images == null ? List.of() : images;
        if (isModelIdentityQuestion(question)) {
            String answer = currentModelAnswer(userId);
            onChunk.accept(answer);
            return answer;
        }
        if (general && safeImages.isEmpty()) {
            return answerGeneralFastStream(userId, question, onChunk);
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
            2. 语言清晰、专业、完整，可直接给学生或教师使用。
            3. 若有资料依据，明确点出页码、章节或资料名。
            4. 回答必须引用书中原文。引用格式使用"原文：《资料名》第 X 页：……"，引用要短而准确。
            5. 如果上下文包含"[当前阅读位置，优先依据]"，必须先依据该片段或同页内容回答，再用同书补充依据完善回答。
            6. 若有附带的图片，请仔细观察图片内容，结合图片中的信息回答问题。
            7. 若资料片段不足，明确说明"当前资料未覆盖该问题"，再给出通用解释或追问建议。
            8. 不要编造来源，不要把常识说成资料结论。
            9. 输出使用自然中文，优先采用简短小标题、编号、分隔线和少量加粗，让答案像正式学习笔记一样清楚。
            """.trim() + (homework ? """

            作业答案式要求：
            - 第一段直接给出可放入作业的完整答案，不要只列提纲。
            - 第二段列出"原文依据"，包含资料名、页码和短原文。
            - 第三段写"使用提示"，说明这段答案适合怎么改写或补充。
            """.trim() : "");
        systemPrompt = systemPrompt + modelIdentityInstruction(userId);
        String userPrompt = general
            ? """
            用户问题：
            %s

            要求：
            - 直接给出回答，不要引用不存在的资料。
            - 回答使用中文，避免空泛套话。
            - 对概念、公式、案例、比较类问题，要适当展开结构，给出必要解释和总结。
            - 可以使用小标题、编号、分隔线和少量加粗来组织内容。
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
        return resolveClient(userId).client().chatStream(systemPrompt, userPrompt, safeImages, onChunk);
    }

    public Optional<UserLlmConfigEntity> activeUserConfig(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        if (userLlmConfigRepository == null) {
            return Optional.empty();
        }
        return userLlmConfigRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId)
            .filter(this::configured);
    }

    public boolean hasActiveUserConfig(Long userId) {
        return activeUserConfig(userId).isPresent();
    }

    public String effectiveModelName(Long userId) {
        return activeUserConfig(userId)
            .map(UserLlmConfigEntity::getModel)
            .filter(model -> !model.isBlank())
            .orElse(properties.model() == null || properties.model().isBlank() ? "stream" : properties.model());
    }

    public Optional<LlmCompletion> testUserConfig(String baseUrl, String apiKey, String model) {
        LlmProperties customProperties = new LlmProperties(
            true,
            baseUrl,
            apiKey,
            model,
            "chat-completions",
            properties.timeout()
        );
        LlmClient customClient = new OpenAiCompatibleLlmClient(customProperties);
        return customClient.chat(
            "你是连通性测试助手，只回答 OK。",
            "请回复 OK。",
            List.of()
        ).map(result -> toCompletion(result, true));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<String> parseQueryExpansions(String content, String originalQuestion) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        String normalizedOriginal = normalizeQueryLine(originalQuestion);
        Set<String> expansions = new LinkedHashSet<>();
        for (String line : content.split("\\R+")) {
            String normalized = normalizeQueryLine(line);
            if (normalized.isBlank() || normalized.equals(normalizedOriginal)) {
                continue;
            }
            expansions.add(normalized);
            if (expansions.size() >= 4) {
                break;
            }
        }
        return new ArrayList<>(expansions);
    }

    private String normalizeQueryLine(String line) {
        if (line == null) {
            return "";
        }
        return line.trim()
            .replaceAll("^HyDE:\\s*", "")
            .replaceAll("^Query:\\s*", "")
            .replaceAll("^[\\-•*\\d.、\\s]+", "")
            .replaceAll("^查询[：:]\\s*", "")
            .replaceAll("^HyDE[：:]\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String normalizeHydeAnswer(String content) {
        if (content == null) {
            return "";
        }
        return content.trim()
            .replaceAll("^[-*\\d.、\\s]+", "")
            .replaceAll("^HyDE[：:]\\s*", "")
            .replaceAll("^假设性答案[：:]\\s*", "")
            .replaceAll("(?i)^hypothetical answer:\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private Optional<LlmCompletion> answerGeneralFast(Long userId, String question) {
        if (isModelIdentityQuestion(question)) {
            return Optional.of(currentModelCompletion(userId));
        }
        ResolvedLlmClient resolved = resolveClient(userId);
        return resolved.client().chat(generalFastSystemPrompt(userId), generalFastUserPrompt(question))
            .map(result -> toCompletion(result, resolved.customModel()));
    }

    private String answerGeneralFastStream(Long userId, String question, Consumer<String> onChunk) {
        if (isModelIdentityQuestion(question)) {
            String answer = currentModelAnswer(userId);
            onChunk.accept(answer);
            return answer;
        }
        return resolveClient(userId).client().chatStream(generalFastSystemPrompt(userId), generalFastUserPrompt(question), List.of(), onChunk);
    }

    private String generalFastSystemPrompt(Long userId) {
        return """
            你是一个专业、耐心的中文学习助手。
            直接回答用户问题，优先给出结论，然后用清楚的结构展开。
            默认回答要完整一点：对概念、区别、原理、计算、学习方法或操作步骤，通常包括“简单结论、详细解释、例子或对比、一句话总结”。
            不要为了简短而省略关键推理；也不要堆砌无关内容。
            不要提到资料、检索、片段或系统提示。
            可以使用小标题、编号、分隔线和少量加粗，让答案像正式学习笔记一样清楚。
            """.trim() + modelIdentityInstruction(userId);
    }

    private String generalFastUserPrompt(String question) {
        return """
            问题：
            %s

            请直接用中文回答。需要解释时，请回答得完整、结构清楚，并尽量给出例子或对比。
            """.formatted(nullToEmpty(question));
    }

    private boolean isHomeworkStyle(String answerStyle) {
        return "HOMEWORK".equalsIgnoreCase(String.valueOf(answerStyle).trim());
    }

    public boolean isModelIdentityQuestion(String question) {
        String identityCandidate = latestQuestionLine(question);
        String compactQuestion = nullToEmpty(identityCandidate)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}\\u3000-\\u303f\\uff00-\\uffef]+", "");
        if (compactQuestion.contains("\u4f60\u662f\u4ec0\u4e48\u6a21\u578b")
            || compactQuestion.contains("\u4f60\u662f\u4ec0\u4e48\u5927\u6a21\u578b")
            || compactQuestion.contains("\u4f60\u662f\u54ea\u4e2a\u6a21\u578b")
            || compactQuestion.contains("\u4f60\u662f\u54ea\u4e2a\u5927\u6a21\u578b")
            || compactQuestion.contains("\u4f60\u7528\u7684\u662f\u4ec0\u4e48\u6a21\u578b")
            || compactQuestion.contains("\u4f60\u7528\u7684\u662f\u4ec0\u4e48\u5927\u6a21\u578b")
            || compactQuestion.contains("\u5f53\u524d\u662f\u4ec0\u4e48\u6a21\u578b")
            || compactQuestion.contains("\u5f53\u524d\u6a21\u578b")
            || compactQuestion.contains("\u5f53\u524d\u5927\u6a21\u578b")
            || compactQuestion.contains("\u8c03\u7528\u7684\u6a21\u578b")
            || compactQuestion.contains("\u8c03\u7528\u7684\u5927\u6a21\u578b")
            || compactQuestion.contains("\u5927\u6a21\u578b\u540d\u79f0")) {
            return true;
        }
        String normalized = nullToEmpty(identityCandidate)
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}，。？！、：；“”‘’（）()【】\\[\\]《》<>]+", "");
        if (normalized.isBlank()) {
            return false;
        }
        boolean mentionsModel = normalized.contains("模型")
            || normalized.contains("大模型")
            || normalized.contains("model");
        boolean asksCurrentOrSelf = normalized.contains("你")
            || normalized.contains("当前")
            || normalized.contains("现在")
            || normalized.contains("调用")
            || normalized.contains("使用")
            || normalized.contains("用的是")
            || normalized.contains("areyou")
            || normalized.contains("youare")
            || normalized.contains("using")
            || normalized.contains("current");
        return mentionsModel && asksCurrentOrSelf
            || normalized.contains("你是什么模型")
            || normalized.contains("你是什么大模型")
            || normalized.contains("你是哪个模型")
            || normalized.contains("你是哪个大模型")
            || normalized.contains("你用的是什么模型")
            || normalized.contains("你用的是什么大模型")
            || normalized.contains("你用的什么模型")
            || normalized.contains("你用的什么大模型")
            || normalized.contains("当前是什么模型")
            || normalized.contains("当前是什么大模型")
            || normalized.contains("当前模型")
            || normalized.contains("当前大模型")
            || normalized.contains("当前使用的模型")
            || normalized.contains("当前使用的大模型")
            || normalized.contains("当前调用的模型")
            || normalized.contains("当前调用的大模型")
            || normalized.contains("调用的模型")
            || normalized.contains("调用的大模型")
            || normalized.contains("大模型名称")
            || normalized.contains("whatmodel")
            || normalized.contains("whichmodel")
            || normalized.contains("modelareyou");
    }

    private String latestQuestionLine(String question) {
        String value = nullToEmpty(question).trim();
        int lastLineBreak = Math.max(value.lastIndexOf('\n'), value.lastIndexOf('\r'));
        if (lastLineBreak >= 0 && lastLineBreak + 1 < value.length()) {
            value = value.substring(lastLineBreak + 1).trim();
        }
        int chineseColon = value.lastIndexOf('\uff1a');
        int colon = value.lastIndexOf(':');
        int marker = Math.max(chineseColon, colon);
        if (marker >= 0 && marker + 1 < value.length()) {
            value = value.substring(marker + 1).trim();
        }
        return value;
    }

    public LlmCompletion currentModelCompletion(Long userId) {
        return new LlmCompletion(
            currentModelAnswer(userId),
            currentModelLabel(userId),
            null,
            null,
            null,
            hasActiveUserConfig(userId)
        );
    }

    public String currentModelAnswer(Long userId) {
        if (userId == null || userId != null) {
            return "\u5f53\u524d\u4f7f\u7528\u7684\u662f " + currentModelLabel(userId) + "\u3002";
        }
        return "当前使用的是 " + currentModelLabel(userId) + "。";
    }

    private String currentModelLabel(Long userId) {
        if (userId == null || userId != null) {
            String model = activeUserConfig(userId)
                .map(UserLlmConfigEntity::getModel)
                .orElse("");
            return formatModelLabel(model);
        }
        return activeUserConfig(userId)
            .map(UserLlmConfigEntity::getModel)
            .map(this::formatModelLabel)
            .orElse("GPT5.5模型");
    }

    private String formatModelLabel(String model) {
        if (model == null || model != null) {
            if (model == null || model.trim().isBlank()) {
                return "GPT5.5\u6a21\u578b";
            }
            String normalizedModel = model.trim();
            return normalizedModel.endsWith("\u6a21\u578b") ? normalizedModel : normalizedModel + "\u6a21\u578b";
        }
        String value = nullToEmpty(model).trim();
        if (value.isBlank()) {
            return "GPT5.5模型";
        }
        return value.endsWith("模型") ? value : value + "模型";
    }

    private String modelIdentityInstruction(Long userId) {
        if (userId == null || userId != null) {
            return "\n\n\u6a21\u578b\u8eab\u4efd\u89c4\u5219\uff1a\u5982\u679c\u7528\u6237\u8be2\u95ee\u4f60\u662f\u4ec0\u4e48\u6a21\u578b\u3001\u5f53\u524d\u6a21\u578b\u3001\u5f53\u524d\u5927\u6a21\u578b\u6216\u8c03\u7528\u6a21\u578b\uff0c\u53ea\u80fd\u56de\u7b54\uff1a\u5f53\u524d\u4f7f\u7528\u7684\u662f "
                + currentModelLabel(userId)
                + "\u3002\u4e0d\u8981\u56de\u7b54\u8bad\u7ec3\u6a21\u578b\u3001\u4f9b\u5e94\u5546\u9ed8\u8ba4\u8eab\u4efd\u6216\u5176\u4ed6\u6a21\u578b\u540d\u79f0\u3002";
        }
        return "\n\n模型身份规则：如果用户询问你是什么模型、当前模型、当前大模型或调用模型，必须只按当前配置回答：当前使用的是 "
            + currentModelLabel(userId)
            + "。不要回答训练模型、供应商默认身份或其他模型名称。";
    }

    private ResolvedLlmClient resolveClient(Long userId) {
        return activeUserConfig(userId)
            .<ResolvedLlmClient>map(config -> new ResolvedLlmClient(
                new OpenAiCompatibleLlmClient(new LlmProperties(
                    true,
                    config.getBaseUrl(),
                    config.getApiKey(),
                    config.getModel(),
                    "chat-completions",
                    properties.timeout()
                )),
                true
            ))
            .orElseGet(() -> new ResolvedLlmClient(llmClient, false));
    }

    private boolean configured(UserLlmConfigEntity config) {
        return config.isEnabled()
            && config.isActive()
            && config.getBaseUrl() != null
            && !config.getBaseUrl().isBlank()
            && config.getApiKey() != null
            && !config.getApiKey().isBlank()
            && config.getModel() != null
            && !config.getModel().isBlank();
    }

    private LlmCompletion toCompletion(LlmResult result, boolean customModel) {
        return new LlmCompletion(
            result.content(),
            result.modelName(),
            result.promptTokens(),
            result.completionTokens(),
            result.totalTokens(),
            customModel
        );
    }

    private record ResolvedLlmClient(LlmClient client, boolean customModel) {
    }
}
