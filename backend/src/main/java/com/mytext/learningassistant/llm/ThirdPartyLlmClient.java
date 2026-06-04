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

/**
 * 第三方 LLM 客户端 — 项目中最高层的 AI 调用入口。
 *
 * 作用：封装了所有 LLM 调用逻辑（问答、摘要、查询扩展、HyDE 等），
 * 同时自动处理"用户自定义模型 vs 系统默认模型"的路由。
 *
 * 核心逻辑：
 * 1. 如果用户有激活的自定义 LLM 配置 → 用用户的配置创建临时 LlmClient
 * 2. 否则 → 使用系统默认的 LlmClient
 *
 * 支持的功能：
 * - answer() / answerStream() — 通用问答和资料问答
 * - summarize() — 资料摘要生成
 * - expandQuery() — 查询扩展（生成 2-4 个变体查询）
 * - generateHydeAnswer() — HyDE 假设性答案生成
 * - testUserConfig() — 测试用户自定义 LLM 配置连通性
 * - isModelIdentityQuestion() — 识别"你是什么模型"类问题
 */
@Component
public class ThirdPartyLlmClient {

    private final LlmClient llmClient;                           // 系统默认 LLM 客户端
    private final LlmProperties properties;                      // 系统默认 LLM 配置
    private final UserLlmConfigRepository userLlmConfigRepository; // 用户自定义配置仓库

    /** 简化构造器（测试用，无用户配置仓库） */
    public ThirdPartyLlmClient(LlmClient llmClient) {
        this(llmClient, new LlmProperties(false, "", "", "", "chat-completions", java.time.Duration.ofSeconds(20)), null);
    }

    /** 完整构造器（Spring 自动注入） */
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

    // ===== 问答方法（重载链：从简单到完整） =====

    /** 简化问答（无图片、资料模式） */
    public Optional<LlmCompletion> answer(String question, List<String> excerpts) {
        return answer(question, excerpts, List.of());
    }

    /** 带图片的问答 */
    public Optional<LlmCompletion> answer(String question, List<String> excerpts, List<LlmImage> images) {
        return answer(question, excerpts, images, false);
    }

    /** 指定通用/资料模式 */
    public Optional<LlmCompletion> answer(String question, List<String> excerpts, List<LlmImage> images, boolean general) {
        return answer(question, excerpts, images, general, "STUDY");
    }

    /** 指定回答风格 */
    public Optional<LlmCompletion> answer(String question, List<String> excerpts, List<LlmImage> images, boolean general, String answerStyle) {
        return answer(null, question, excerpts, images, general, answerStyle);
    }

    /**
     * 完整问答方法 — 所有重载最终都走到这里。
     *
     * 流程：
     * 1. 如果用户问"你是什么模型" → 直接返回当前模型名（不调用 LLM）
     * 2. 如果是通用模式且无图片 → 走快速通道（简化提示词）
     * 3. 构造系统提示词（通用模式 / 资料模式 / 作业模式）
     * 4. 构造用户提示词（问题 + 资料片段）
     * 5. 解析客户端（用户自定义 or 系统默认）
     * 6. 调用 LLM 获取回答
     *
     * @param userId      当前用户 ID（用于查找自定义模型配置）
     * @param question    用户问题
     * @param excerpts    检索到的资料片段列表
     * @param images      附带的图片（多模态问答）
     * @param general     是否是通用模式（不绑定资料）
     * @param answerStyle 回答风格（"STUDY"=学习模式，"HOMEWORK"=作业模式）
     * @return LLM 回答（含 token 消耗等元数据）
     */
    public Optional<LlmCompletion> answer(
        Long userId, String question, List<String> excerpts, List<LlmImage> images,
        boolean general, String answerStyle
    ) {
        List<String> safeExcerpts = excerpts == null ? List.of() : excerpts;
        List<LlmImage> safeImages = images == null ? List.of() : images;

        // 特殊处理：用户问"你是什么模型"时直接返回，不调用 LLM
        if (isModelIdentityQuestion(question)) {
            return Optional.of(currentModelCompletion(userId));
        }
        // 通用模式 + 无图片 → 走快速通道
        if (general && safeImages.isEmpty()) {
            return answerGeneralFast(userId, question);
        }

        boolean homework = isHomeworkStyle(answerStyle);
        // 构造系统提示词（通用模式用简短版，资料模式用完整版）
        String systemPrompt = general
            ? "你是一个专业的学习助手。直接回答用户的问题..."  // 简化展示
            : "你是课程学习助手的商用问答引擎。\n目标：\n1. 优先使用提供的课程资料片段作答。\n2. 语言简洁专业...\n"
              + (homework ? "\n作业答案式要求：\n- 第一段直接给出可放入作业的完整答案..." : "");
        systemPrompt = systemPrompt + modelIdentityInstruction(userId);  // 追加模型身份规则

        // 构造用户提示词
        String userPrompt = general
            ? "用户问题：\n%s\n\n要求：\n- 直接给出回答...".formatted(nullToEmpty(question))
            : "用户问题：\n%s\n\n资料检索片段：\n%s\n\n要求：\n- 先给出直接结论...".formatted(
                nullToEmpty(question),
                safeExcerpts.isEmpty() ? "无命中文献片段" : String.join("\n\n", safeExcerpts),
                homework ? "- 按作业答案式输出..." : ""
            );

        // 解析使用哪个 LLM 客户端（用户自定义 or 系统默认）
        ResolvedLlmClient resolved = resolveClient(userId);
        return resolved.client().chat(systemPrompt, userPrompt, safeImages)
            .map(result -> toCompletion(result, resolved.customModel()));
    }

    /**
     * 资料摘要生成 — 取资料的前几个片段，让 LLM 生成摘要。
     * 使用系统默认模型（不走用户自定义配置）。
     */
    public Optional<LlmCompletion> summarize(String materialTitle, List<String> excerpts) {
        if (excerpts == null || excerpts.isEmpty()) return Optional.empty();
        String systemPrompt = "你是课程资料总结引擎。请基于给定片段生成可用于复习、答辩和再次提问的摘要...";
        String userPrompt = "资料标题：%s\n\n资料片段：\n%s".formatted(nullToEmpty(materialTitle), String.join("\n\n", excerpts));
        return llmClient.chat(systemPrompt, userPrompt)
            .map(result -> new LlmCompletion(result.content(), result.modelName()));
    }

    /**
     * 查询扩展 — 为 RAG 检索生成 2-4 个变体查询。
     * 包含关键词查询和 HyDE 假设性答案短句。
     */
    public Optional<List<String>> expandQuery(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        String systemPrompt = "你是 RAG 检索查询改写器。根据用户问题生成 2 到 4 条适合检索课程资料的查询变体...";
        return llmClient.chat(systemPrompt, "用户问题：\n%s".formatted(nullToEmpty(question)))
            .map(result -> parseQueryExpansions(result.content(), question))
            .filter(expansions -> !expansions.isEmpty());
    }

    /** HyDE 假设性答案生成 — 生成一段可能出现在资料中的答案，用于向量检索 */
    public Optional<String> generateHydeAnswer(String question) {
        if (question == null || question.isBlank()) return Optional.empty();
        String systemPrompt = "你是 RAG HyDE 查询生成器。根据用户问题生成一段可能出现在课程资料中的假设性答案...";
        return llmClient.chat(systemPrompt, "用户问题：\n%s".formatted(nullToEmpty(question)))
            .map(LlmResult::content).map(this::normalizeHydeAnswer).filter(answer -> !answer.isBlank());
    }

    // ===== 流式问答方法（重载链） =====

    /** 流式问答（简化版） */
    public String answerStream(String question, List<String> excerpts, List<LlmImage> images, Consumer<String> onChunk) {
        return answerStream(question, excerpts, images, onChunk, false);
    }
    public String answerStream(String question, List<String> excerpts, List<LlmImage> images, Consumer<String> onChunk, boolean general) {
        return answerStream(question, excerpts, images, onChunk, general, "STUDY");
    }
    public String answerStream(String question, List<String> excerpts, List<LlmImage> images, Consumer<String> onChunk, boolean general, String answerStyle) {
        return answerStream(null, question, excerpts, images, onChunk, general, answerStyle);
    }

    /**
     * 完整流式问答 — 与 answer() 逻辑相同，但使用 chatStream() 逐字输出。
     * onChunk 回调会在收到每个文本增量时被调用，实现"打字机"效果。
     */
    public String answerStream(Long userId, String question, List<String> excerpts, List<LlmImage> images, Consumer<String> onChunk, boolean general, String answerStyle) {
        // ... 与 answer() 相同的提示词构造逻辑 ...
        List<String> safeExcerpts = excerpts == null ? List.of() : excerpts;
        List<LlmImage> safeImages = images == null ? List.of() : images;
        if (isModelIdentityQuestion(question)) { String answer = currentModelAnswer(userId); onChunk.accept(answer); return answer; }
        if (general && safeImages.isEmpty()) { return answerGeneralFastStream(userId, question, onChunk); }
        // ... 构造提示词，调用 resolveClient(userId).client().chatStream() ...
        return resolveClient(userId).client().chatStream(systemPrompt, userPrompt, safeImages, onChunk);
    }

    // ===== 用户自定义模型相关 =====

    /** 获取用户当前激活的 LLM 配置 */
    public Optional<UserLlmConfigEntity> activeUserConfig(Long userId) {
        if (userId == null || userLlmConfigRepository == null) return Optional.empty();
        return userLlmConfigRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(userId)
            .filter(this::configured);  // 只返回配置完整的
    }

    /** 用户是否有激活的自定义模型 */
    public boolean hasActiveUserConfig(Long userId) { return activeUserConfig(userId).isPresent(); }

    /** 获取当前生效的模型名称（用户自定义 or 系统默认） */
    public String effectiveModelName(Long userId) {
        return activeUserConfig(userId).map(UserLlmConfigEntity::getModel)
            .filter(model -> !model.isBlank())
            .orElse(properties.model() == null || properties.model().isBlank() ? "stream" : properties.model());
    }

    /**
     * 测试用户自定义 LLM 配置连通性。
     * 用用户的 baseUrl + apiKey + model 创建临时客户端，发送"请回复 OK"测试。
     */
    public Optional<LlmCompletion> testUserConfig(String baseUrl, String apiKey, String model) {
        LlmProperties customProperties = new LlmProperties(true, baseUrl, apiKey, model, "chat-completions", properties.timeout());
        LlmClient customClient = new OpenAiCompatibleLlmClient(customProperties);
        return customClient.chat("你是连通性测试助手，只回答 OK。", "请回复 OK。", List.of())
            .map(result -> toCompletion(result, true));
    }

    // ===== 模型身份识别 =====

    /**
     * 判断用户是否在问"你是什么模型"类问题。
     * 支持多种中文表达方式（"你是什么模型"、"当前模型"、"调用的是什么大模型"等）。
     * 只检查最后一行文本（多轮对话中用户可能在最后一行才问）。
     */
    public boolean isModelIdentityQuestion(String question) {
        String compactQuestion = nullToEmpty(latestQuestionLine(question))
            .toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}\\u3000-\\u303f\\uff00-\\uffef]+", "");
        // 精确匹配常见表达
        if (compactQuestion.contains("你是什么模型") || compactQuestion.contains("你是什么大模型")
            || compactQuestion.contains("你是哪个模型") || compactQuestion.contains("你是哪个大模型")
            || compactQuestion.contains("你用的是什么模型") || compactQuestion.contains("当前是什么模型")
            || compactQuestion.contains("当前模型") || compactQuestion.contains("调用的模型")
            || compactQuestion.contains("大模型名称")) {
            return true;
        }
        // 模糊匹配：包含"模型"+"你/当前/调用/使用"等关键词
        String normalized = nullToEmpty(latestQuestionLine(question)).toLowerCase(Locale.ROOT)
            .replaceAll("[\\s\\p{Punct}，。？！、：；\"\"''（）()【】\\[\\]《》<>]+", "");
        boolean mentionsModel = normalized.contains("模型") || normalized.contains("大模型") || normalized.contains("model");
        boolean asksCurrent = normalized.contains("你") || normalized.contains("当前") || normalized.contains("调用") || normalized.contains("使用");
        return mentionsModel && asksCurrent;
    }

    /** 提取最后一行文本（多轮对话中用户可能在最后一行才问模型身份） */
    private String latestQuestionLine(String question) {
        String value = nullToEmpty(question).trim();
        int lastLineBreak = Math.max(value.lastIndexOf('\n'), value.lastIndexOf('\r'));
        if (lastLineBreak >= 0 && lastLineBreak + 1 < value.length()) { value = value.substring(lastLineBreak + 1).trim(); }
        return value;
    }

    /** 获取当前模型的 LlmCompletion（用于"你是什么模型"问题的回答） */
    public LlmCompletion currentModelCompletion(Long userId) {
        return new LlmCompletion(currentModelAnswer(userId), currentModelLabel(userId), null, null, null, hasActiveUserConfig(userId));
    }

    /** 获取当前模型的回答文本 */
    public String currentModelAnswer(Long userId) { return "当前使用的是 " + currentModelLabel(userId) + "。"; }

    /** 获取当前模型的显示标签（如 "deepseek-chat模型"） */
    private String currentModelLabel(Long userId) {
        String model = activeUserConfig(userId).map(UserLlmConfigEntity::getModel).orElse("");
        return formatModelLabel(model);
    }

    /** 格式化模型名称标签 */
    private String formatModelLabel(String model) {
        String value = nullToEmpty(model).trim();
        if (value.isBlank()) return "GPT5.5模型";
        return value.endsWith("模型") ? value : value + "模型";
    }

    // ===== 内部辅助方法 =====

    /**
     * 解析使用哪个 LLM 客户端。
     * 如果用户有激活的自定义配置 → 用用户的配置创建新客户端
     * 否则 → 使用系统默认客户端
     */
    private ResolvedLlmClient resolveClient(Long userId) {
        return activeUserConfig(userId)
            .<ResolvedLlmClient>map(config -> new ResolvedLlmClient(
                new OpenAiCompatibleLlmClient(new LlmProperties(
                    true, config.getBaseUrl(), config.getApiKey(), config.getModel(),
                    "chat-completions", properties.timeout()
                )), true))
            .orElseGet(() -> new ResolvedLlmClient(llmClient, false));
    }

    /** 检查用户配置是否完整（enabled + active + 三个必填字段都有值） */
    private boolean configured(UserLlmConfigEntity config) {
        return config.isEnabled() && config.isActive()
            && config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
            && config.getApiKey() != null && !config.getApiKey().isBlank()
            && config.getModel() != null && !config.getModel().isBlank();
    }

    private String nullToEmpty(String value) { return value == null ? "" : value; }
    private boolean isHomeworkStyle(String answerStyle) { return "HOMEWORK".equalsIgnoreCase(String.valueOf(answerStyle).trim()); }
    private LlmCompletion toCompletion(LlmResult result, boolean customModel) { return new LlmCompletion(result.content(), result.modelName(), result.promptTokens(), result.completionTokens(), result.totalTokens(), customModel); }

    /** 解析查询扩展结果（去掉编号、HyDE 前缀等，去重） */
    private List<String> parseQueryExpansions(String content, String originalQuestion) {
        if (content == null || content.isBlank()) return List.of();
        String normalizedOriginal = normalizeQueryLine(originalQuestion);
        Set<String> expansions = new LinkedHashSet<>();
        for (String line : content.split("\\R+")) {
            String normalized = normalizeQueryLine(line);
            if (normalized.isBlank() || normalized.equals(normalizedOriginal)) continue;
            expansions.add(normalized);
            if (expansions.size() >= 4) break;
        }
        return new ArrayList<>(expansions);
    }

    /** 标准化查询行（去掉编号、前缀等） */
    private String normalizeQueryLine(String line) {
        if (line == null) return "";
        return line.trim().replaceAll("^HyDE:\\s*", "").replaceAll("^Query:\\s*", "")
            .replaceAll("^[\\-•*\\d.、\\s]+", "").replaceAll("^查询[：:]\\s*", "")
            .replaceAll("^HyDE[：:]\\s*", "").replaceAll("\\s+", " ").trim();
    }

    /** 标准化 HyDE 答案 */
    private String normalizeHydeAnswer(String content) {
        if (content == null) return "";
        return content.trim().replaceAll("^[-*\\d.、\\s]+", "").replaceAll("^HyDE[：:]\\s*", "")
            .replaceAll("^假设性答案[：:]\\s*", "").replaceAll("(?i)^hypothetical answer:\\s*", "")
            .replaceAll("\\s+", " ").trim();
    }

    /** 模型身份指令（追加到系统提示词末尾，防止 LLM 说出真实模型名） */
    private String modelIdentityInstruction(Long userId) {
        return "\n\n模型身份规则：如果用户询问你是什么模型、当前模型、当前大模型或调用模型，必须只按当前配置回答：当前使用的是 "
            + currentModelLabel(userId) + "。不要回答训练模型、供应商默认身份或其他模型名称。";
    }

    private Optional<LlmCompletion> answerGeneralFast(Long userId, String question) {
        if (isModelIdentityQuestion(question)) return Optional.of(currentModelCompletion(userId));
        return resolveClient(userId).client().chat(generalFastSystemPrompt(userId), generalFastUserPrompt(question))
            .map(result -> toCompletion(result, resolveClient(userId).customModel()));
    }

    private String answerGeneralFastStream(Long userId, String question, Consumer<String> onChunk) {
        if (isModelIdentityQuestion(question)) { String answer = currentModelAnswer(userId); onChunk.accept(answer); return answer; }
        return resolveClient(userId).client().chatStream(generalFastSystemPrompt(userId), generalFastUserPrompt(question), List.of(), onChunk);
    }

    private String generalFastSystemPrompt(Long userId) {
        return "你是一个专业、耐心的中文学习助手。直接回答用户问题..." + modelIdentityInstruction(userId);
    }

    private String generalFastUserPrompt(String question) {
        return "问题：\n%s\n\n请直接用中文回答。".formatted(nullToEmpty(question));
    }

    /** 内部记录：解析后的 LLM 客户端 + 是否是用户自定义模型 */
    private record ResolvedLlmClient(LlmClient client, boolean customModel) {}
}
