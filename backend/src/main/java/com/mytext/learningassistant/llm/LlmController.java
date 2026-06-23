package com.mytext.learningassistant.llm;

import com.mytext.learningassistant.common.ApiResponse;
import com.mytext.learningassistant.common.BusinessException;
import com.mytext.learningassistant.security.OutboundUrlGuard;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LLM（大语言模型）配置管理控制器。
 * <p>
 * 提供以下 HTTP 接口：
 * <ul>
 *   <li><b>系统模型状态查询</b>：查看系统默认 LLM 是否已配置</li>
 *   <li><b>用户自定义模型配置</b>：保存、查询、删除用户自定义的 LLM 连接信息</li>
 *   <li><b>连通性测试</b>：测试用户自定义 LLM 的 baseUrl + apiKey + model 是否可用</li>
 * </ul>
 * <p>
 * 用户可以配置自己的 LLM（如 DeepSeek、通义千问等 OpenAI 兼容接口），
 * 配置后系统会优先使用用户自定义模型，而非系统默认模型。
 *
 * @see ThirdPartyLlmClient 第三方 LLM 调用客户端
 * @see UserLlmConfigEntity 用户自定义 LLM 配置实体
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    /** 系统默认 LLM 配置属性（包含 baseUrl、apiKey、model 等） */
    private final LlmProperties properties;

    /** 用户自定义 LLM 配置仓库，用于持久化用户的模型连接信息 */
    private final UserLlmConfigRepository userLlmConfigRepository;

    /** 第三方 LLM 客户端，用于测试连通性和调用 AI 模型 */
    private final ThirdPartyLlmClient thirdPartyLlmClient;

    /** 出站 URL 安全检查，确保用户配置的 URL 是合法的公网地址 */
    private final OutboundUrlGuard outboundUrlGuard;

    /**
     * 构造方法，由 Spring 自动注入所有依赖。
     *
     * @param properties               系统默认 LLM 配置
     * @param userLlmConfigRepository  用户自定义 LLM 配置仓库
     * @param thirdPartyLlmClient      第三方 LLM 客户端
     * @param outboundUrlGuard         出站 URL 安全检查
     */
    public LlmController(
        LlmProperties properties,
        UserLlmConfigRepository userLlmConfigRepository,
        ThirdPartyLlmClient thirdPartyLlmClient,
        OutboundUrlGuard outboundUrlGuard
    ) {
        this.properties = properties;
        this.userLlmConfigRepository = userLlmConfigRepository;
        this.thirdPartyLlmClient = thirdPartyLlmClient;
        this.outboundUrlGuard = outboundUrlGuard;
    }

    /**
     * 查询系统默认 LLM 的配置状态。
     * <p>
     * 返回系统是否已配置完整的 LLM 连接信息（enabled + baseUrl + apiKey + model），
     * 前端可据此提示用户是否需要配置自定义模型。
     *
     * @return LLM 状态响应，包含 enabled（是否启用）、configured（是否完整配置）、提示信息
     */
    @GetMapping("/status")
    public ApiResponse<LlmStatusResponse> status() {
        boolean configured = properties.configured();
        String message = configured
            ? "大模型已配置，智能问答会优先调用第三方模型。"
            : "大模型未配置完整，系统会使用本地 RAG 兜底答案。";
        return ApiResponse.ok(new LlmStatusResponse(properties.enabled(), configured, message));
    }

    /**
     * 查询当前用户的自定义 LLM 配置信息。
     * <p>
     * 返回当前激活的模型配置（如果有）以及所有历史配置列表。
     * 每个配置只暴露是否存在 API Key，不返回实际的密钥值（安全考虑）。
     *
     * @param currentUserId 当前登录用户 ID（由拦截器注入）
     * @return 用户 LLM 配置响应，包含是否启用、当前激活配置、配置列表
     */
    //查看AI是否可用
    @GetMapping("/user-config")
    public ApiResponse<UserLlmConfigResponse> userConfig(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(toResponse(currentUserId));
    }

    /**
     * 保存（新增或更新）用户的自定义 LLM 配置。
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>如果 request.enabled = false，停用所有配置并返回</li>
     *   <li>校验 baseUrl 是否为合法公网 URL（防止内网穿透攻击）</li>
     *   <li>如果 request.id 不为空，查找已有配置进行更新；否则新建配置</li>
     *   <li>校验 baseUrl、apiKey、model 三个必填字段</li>
     *   <li>停用该用户的所有其他配置，将当前配置设为唯一激活的</li>
     * </ol>
     *
     * @param currentUserId 当前登录用户 ID
     * @param request       用户 LLM 配置请求（包含 baseUrl、apiKey、model 等）
     * @return 保存后的用户 LLM 配置响应
     */

    //保存用户自己配置的AI配置
    @PutMapping("/user-config")
    @Transactional
    public ApiResponse<UserLlmConfigResponse> saveUserConfig(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestBody UserLlmConfigRequest request
    ) {
        // 如果用户选择停用自定义模型，清除所有激活状态
        if (!request.enabled()) {
            deactivateUserConfigs(currentUserId);
            return ApiResponse.ok(toResponse(currentUserId));
        }

        // 校验 baseUrl 是合法的公网 HTTP 地址（安全防护）
        String baseUrl = normalize(request.baseUrl());
        if (baseUrl != null) {
            outboundUrlGuard.requirePublicHttpUrl(baseUrl, true);
        }

        // 查找已有配置或创建新配置
        UserLlmConfigEntity config = request.id() == null
            ? new UserLlmConfigEntity()
            : userLlmConfigRepository.findByIdAndUserId(request.id(), currentUserId)
                .orElseThrow(() -> new BusinessException(404, "模型配置不存在"));

        // 设置配置字段
        config.setUserId(currentUserId);
        config.setEnabled(true);
        config.setActive(false); // 先设为非激活，后面统一处理
        config.setDisplayName(displayName(request.displayName(), request.model()));
        config.setBaseUrl(baseUrl);
        config.setModel(normalize(request.model()));

        // API Key 只在用户提供了新值时更新（空值表示"不修改"）
        String apiKey = normalize(request.apiKey());
        if (apiKey != null) {
            config.setApiKey(apiKey);
        }
        // 校验三个必填字段都已填写
        if (!configured(config)) {
            throw new BusinessException(400, "请填写 URL 地址、密钥和模型名称");
        }

        // 停用所有其他配置，将当前配置设为唯一激活的
        deactivateUserConfigs(currentUserId);
        config.setActive(true);
        userLlmConfigRepository.save(config);
        return ApiResponse.ok(toResponse(currentUserId));
    }

    /**
     * 测试用户自定义 LLM 配置的连通性。
     * <p>
     * 会用用户提供的 baseUrl + apiKey + model 创建一个临时 LLM 客户端，
     * 发送"请回复 OK"进行连通性测试。返回测试是否成功以及模型名称。
     *
     * @param currentUserId 当前登录用户 ID
     * @param request       用户 LLM 配置请求
     * @return 测试结果，包含是否成功、提示信息和实际模型名
     */
    //测试AI连接
    @PostMapping("/user-config/test")
    public ApiResponse<UserLlmTestResponse> testUserConfig(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestBody UserLlmConfigRequest request
    ) {
        // 查找已有配置（如果有的话），用于补充缺失的字段
        UserLlmConfigEntity existing = request.id() == null
            ? userLlmConfigRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(currentUserId).orElse(null)
            : userLlmConfigRepository.findByIdAndUserId(request.id(), currentUserId).orElse(null);

        String baseUrl = normalize(request.baseUrl());
        String apiKey = normalize(request.apiKey());
        String model = normalize(request.model());

        // 如果用户没有提供新的 apiKey，使用已有配置中的（方便只改 model 测试）
        if (apiKey == null && existing != null) {
            apiKey = existing.getApiKey();
        }
        // 校验三个必填字段
        if (baseUrl == null || apiKey == null || model == null) {
            throw new BusinessException(400, "请填写 URL 地址、密钥和模型名称");
        }
        outboundUrlGuard.requirePublicHttpUrl(baseUrl, true);

        // 执行连通性测试
        return ApiResponse.ok(thirdPartyLlmClient.testUserConfig(baseUrl, apiKey, model)
            .map(result -> new UserLlmTestResponse(true, "连通性测试成功", result.modelName()))
            .orElseGet(() -> new UserLlmTestResponse(false, "连通性测试失败，请检查 URL、密钥和模型名称", model)));
    }

    /**
     * 删除指定的用户自定义 LLM 配置。
     * <p>
     * 只能删除自己的配置。删除后如果没有其他激活的配置，系统会回退到默认模型。
     *
     * @param currentUserId 当前登录用户 ID
     * @param id            要删除的配置 ID
     * @return 删除后的用户 LLM 配置响应
     */
    @DeleteMapping("/user-config/{id}")
    @Transactional
    public ApiResponse<UserLlmConfigResponse> deleteUserConfig(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        UserLlmConfigEntity config = userLlmConfigRepository.findByIdAndUserId(id, currentUserId)
            .orElseThrow(() -> new BusinessException(404, "模型配置不存在"));
        userLlmConfigRepository.delete(config);
        return ApiResponse.ok(toResponse(currentUserId));
    }

    /**
     * 将用户的所有 LLM 配置组装为响应对象。
     * <p>
     * 从数据库加载用户的全部配置，找到当前激活且配置完整的那个作为"当前配置"，
     * 其余的作为历史配置列表返回。
     *
     * @param userId 用户 ID
     * @return 用户 LLM 配置响应
     */
    private UserLlmConfigResponse toResponse(long userId) {
        var configs = userLlmConfigRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        UserLlmConfigEntity activeConfig = configs.stream()
            .filter(config -> config.isActive() && configured(config))
            .findFirst()
            .orElse(null);
        return new UserLlmConfigResponse(
            activeConfig != null,
            activeConfig == null || activeConfig.getBaseUrl() == null ? "" : activeConfig.getBaseUrl(),
            activeConfig == null || activeConfig.getModel() == null ? "" : activeConfig.getModel(),
            activeConfig != null && activeConfig.getApiKey() != null && !activeConfig.getApiKey().isBlank(),
            activeConfig == null ? systemModelLabel() : label(activeConfig),
            activeConfig == null ? null : activeConfig.getId(),
            configs.stream().map(this::toItemResponse).toList()
        );
    }

    /**
     * 将单个配置实体转换为列表项响应。
     * <p>
     * 出于安全考虑，apiKey 只返回是否存在（hasApiKey=true/false），不返回实际值。
     *
     * @param config 用户 LLM 配置实体
     * @return 配置列表项响应
     */
    private UserLlmConfigItemResponse toItemResponse(UserLlmConfigEntity config) {
        return new UserLlmConfigItemResponse(
            config.getId(),
            label(config),
            config.getBaseUrl() == null ? "" : config.getBaseUrl(),
            config.getModel() == null ? "" : config.getModel(),
            config.getApiKey() != null && !config.getApiKey().isBlank(),
            config.isActive()
        );
    }

    /**
     * 停用指定用户的所有自定义 LLM 配置。
     * 在保存新配置之前调用，确保同一时间只有一个配置处于激活状态。
     *
     * @param userId 用户 ID
     */
    private void deactivateUserConfigs(long userId) {
        var configs = userLlmConfigRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        for (UserLlmConfigEntity config : configs) {
            if (config.isActive()) {
                config.setActive(false);
                userLlmConfigRepository.save(config);
            }
        }
    }

    /**
     * 检查配置是否完整（三个必填字段：baseUrl、apiKey、model 都不为空）。
     *
     * @param config 用户 LLM 配置实体
     * @return true 表示配置完整，可以用于调用 LLM
     */
    private boolean configured(UserLlmConfigEntity config) {
        return config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
            && config.getApiKey() != null && !config.getApiKey().isBlank()
            && config.getModel() != null && !config.getModel().isBlank();
    }

    /**
     * 生成配置的显示名称。
     * 优先使用用户自定义的 displayName，为空时使用模型名称，都没有则返回"自定义模型"。
     *
     * @param displayName 用户指定的显示名称
     * @param model       模型名称（如 "deepseek-chat"）
     * @return 显示名称字符串
     */
    private String displayName(String displayName, String model) {
        String value = normalize(displayName);
        if (value != null) {
            return value;
        }
        value = normalize(model);
        return value == null ? "自定义模型" : value;
    }

    /**
     * 获取配置的标签文本（用于前端展示）。
     * 优先返回 displayName，否则根据 model 自动生成。
     *
     * @param config 用户 LLM 配置实体
     * @return 标签文本（如 "deepseek-chat模型"）
     */
    private String label(UserLlmConfigEntity config) {
        String displayName = normalize(config.getDisplayName());
        return displayName == null ? displayName(null, config.getModel()) : displayName;
    }

    /**
     * 获取系统默认模型的显示标签。
     * 如果系统配置了模型名，返回 "xxx模型"；否则返回 "gpt5.5模型"。
     *
     * @return 系统模型显示标签
     */
    private String systemModelLabel() {
        return properties.model() == null || properties.model().isBlank() ? "gpt5.5模型" : properties.model() + "模型";
    }

    /**
     * 标准化字符串：去除首尾空白，空白字符串返回 null。
     * 用于处理用户输入中的空值和空白。
     *
     * @param value 原始字符串
     * @return 标准化后的字符串，空白返回 null
     */
    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

}
