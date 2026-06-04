package com.mytext.learningassistant.llm;

import com.mytext.learningassistant.common.ApiResponse;
import com.mytext.learningassistant.common.BusinessException;

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
 * LLM 配置控制器
 *
 * 提供大语言模型配置的 REST API 接口，包括：
 * - 查看系统 LLM 配置状态
 * - 用户自定义模型配置的增删改查
 * - 模型连通性测试
 */
@RestController
@RequestMapping("/api/llm")
public class LlmController {

    /** 系统级 LLM 配置属性 */
    private final LlmProperties properties;

    /** 用户模型配置数据仓库 */
    private final UserLlmConfigRepository userLlmConfigRepository;

    /** 第三方 LLM 客户端，用于测试模型连通性 */
    private final ThirdPartyLlmClient thirdPartyLlmClient;

    /**
     * 构造函数，注入依赖
     *
     * @param properties               系统级 LLM 配置属性
     * @param userLlmConfigRepository  用户模型配置数据仓库
     * @param thirdPartyLlmClient      第三方 LLM 客户端
     */
    public LlmController(
        LlmProperties properties,
        UserLlmConfigRepository userLlmConfigRepository,
        ThirdPartyLlmClient thirdPartyLlmClient
    ) {
        this.properties = properties;
        this.userLlmConfigRepository = userLlmConfigRepository;
        this.thirdPartyLlmClient = thirdPartyLlmClient;
    }

    /**
     * 获取系统 LLM 配置状态
     *
     * @return 包含 LLM 状态信息的 API 响应，包括是否启用、是否已配置、状态消息
     */
    @GetMapping("/status")
    public ApiResponse<LlmStatusResponse> status() {
        // 检查系统是否已完整配置 LLM
        boolean configured = properties.configured();
        // 根据配置状态生成不同的提示消息
        String message = configured
            ? "大模型已配置，智能问答会优先调用第三方模型。"
            : "大模型未配置完整，系统会使用本地 RAG 兜底答案。";
        return ApiResponse.ok(new LlmStatusResponse(
            properties.enabled(),
            configured,
            message
        ));
    }

    /**
     * 获取当前用户的 LLM 配置列表
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器自动注入）
     * @return 包含用户所有模型配置信息的 API 响应
     */
    @GetMapping("/user-config")
    public ApiResponse<UserLlmConfigResponse> userConfig(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(toResponse(currentUserId));
    }

    /**
     * 保存或更新用户 LLM 配置
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器自动注入）
     * @param request       模型配置请求对象，包含配置详情
     * @return 包含更新后用户所有模型配置信息的 API 响应
     * @throws BusinessException 当配置信息不完整时抛出异常
     */
    @PutMapping("/user-config")
    @Transactional
    public ApiResponse<UserLlmConfigResponse> saveUserConfig(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestBody UserLlmConfigRequest request
    ) {
        // 如果用户禁用了配置，停用所有现有配置
        if (!request.enabled()) {
            deactivateUserConfigs(currentUserId);
            return ApiResponse.ok(toResponse(currentUserId));
        }

        // 如果是新增配置，创建新实体；如果是更新，查找现有配置
        UserLlmConfigEntity config = request.id() == null
            ? new UserLlmConfigEntity()
            : userLlmConfigRepository.findByIdAndUserId(request.id(), currentUserId)
                .orElseThrow(() -> new BusinessException(404, "模型配置不存在"));

        // 设置配置属性
        config.setUserId(currentUserId);
        config.setEnabled(true);
        config.setActive(false);
        config.setDisplayName(displayName(request.displayName(), request.model()));
        config.setBaseUrl(normalize(request.baseUrl()));
        config.setModel(normalize(request.model()));

        // 只有当用户提供了新的 API Key 时才更新（避免覆盖已有的密钥）
        String apiKey = normalize(request.apiKey());
        if (apiKey != null) {
            config.setApiKey(apiKey);
        }

        // 验证配置是否完整（URL、密钥、模型名称都必须填写）
        if (request.enabled() && !configured(config)) {
            throw new BusinessException(400, "请填写 URL 地址、密钥和模型名称");
        }

        // 停用所有现有配置，然后激活当前配置
        deactivateUserConfigs(currentUserId);
        config.setActive(true);
        userLlmConfigRepository.save(config);
        return ApiResponse.ok(toResponse(currentUserId));
    }

    /**
     * 测试用户 LLM 配置的连通性
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器自动注入）
     * @param request       模型配置请求对象，包含要测试的配置信息
     * @return 包含测试结果的 API 响应，包括是否成功、消息、模型名称
     * @throws BusinessException 当配置信息不完整时抛出异常
     */
    @PostMapping("/user-config/test")
    public ApiResponse<UserLlmTestResponse> testUserConfig(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestBody UserLlmConfigRequest request
    ) {
        // 查找现有配置（用于获取已保存的 API Key）
        UserLlmConfigEntity existing = request.id() == null
            ? userLlmConfigRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(currentUserId).orElse(null)
            : userLlmConfigRepository.findByIdAndUserId(request.id(), currentUserId).orElse(null);

        String baseUrl = normalize(request.baseUrl());
        String apiKey = normalize(request.apiKey());
        String model = normalize(request.model());

        // 如果请求中没有提供 API Key，使用已保存的 Key
        if (apiKey == null && existing != null) {
            apiKey = existing.getApiKey();
        }

        // 验证配置完整性
        if (baseUrl == null || apiKey == null || model == null) {
            throw new BusinessException(400, "请填写 URL 地址、密钥和模型名称");
        }

        // 调用第三方客户端测试连通性
        return ApiResponse.ok(thirdPartyLlmClient.testUserConfig(baseUrl, apiKey, model)
            .map(result -> new UserLlmTestResponse(true, "连通性测试成功", result.modelName()))
            .orElseGet(() -> new UserLlmTestResponse(false, "连通性测试失败，请检查 URL、密钥和模型名称", model)));
    }

    /**
     * 删除用户 LLM 配置
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器自动注入）
     * @param id            要删除的配置 ID
     * @return 包含更新后用户所有模型配置信息的 API 响应
     * @throws BusinessException 当配置不存在时抛出异常
     */
    @DeleteMapping("/user-config/{id}")
    @Transactional
    public ApiResponse<UserLlmConfigResponse> deleteUserConfig(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id
    ) {
        // 查找并验证配置是否存在
        UserLlmConfigEntity config = userLlmConfigRepository.findByIdAndUserId(id, currentUserId)
            .orElseThrow(() -> new BusinessException(404, "模型配置不存在"));
        userLlmConfigRepository.delete(config);
        return ApiResponse.ok(toResponse(currentUserId));
    }

    /**
     * 将用户配置转换为响应对象
     *
     * @param userId 用户 ID
     * @return 用户模型配置响应对象
     */
    private UserLlmConfigResponse toResponse(long userId) {
        var configs = userLlmConfigRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        // 查找当前激活且配置完整的配置
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
     * 将单个配置实体转换为列表项响应对象
     *
     * @param config 配置实体
     * @return 配置列表项响应对象
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
     * 停用用户的所有模型配置
     *
     * @param userId 用户 ID
     */
    private void deactivateUserConfigs(long userId) {
        var configs = userLlmConfigRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        // 遍历所有配置，将激活状态设为 false
        for (UserLlmConfigEntity config : configs) {
            if (config.isActive()) {
                config.setActive(false);
                userLlmConfigRepository.save(config);
            }
        }
    }

    /**
     * 检查配置是否完整（URL、密钥、模型名称都已填写）
     *
     * @param config 配置实体
     * @return 如果配置完整返回 true，否则返回 false
     */
    private boolean configured(UserLlmConfigEntity config) {
        return config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
            && config.getApiKey() != null && !config.getApiKey().isBlank()
            && config.getModel() != null && !config.getModel().isBlank();
    }

    /**
     * 生成配置的显示名称
     *
     * @param displayName 用户指定的显示名称
     * @param model       模型名称
     * @return 显示名称，如果都为空则返回 "自定义模型"
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
     * 获取配置的标签文本
     *
     * @param config 配置实体
     * @return 配置的显示标签
     */
    private String label(UserLlmConfigEntity config) {
        String displayName = normalize(config.getDisplayName());
        return displayName == null ? displayName(null, config.getModel()) : displayName;
    }

    /**
     * 获取系统默认模型的标签
     *
     * @return 系统模型标签，如 "gpt-4模型" 或 "gpt5.5模型"
     */
    private String systemModelLabel() {
        return properties.model() == null || properties.model().isBlank() ? "gpt5.5模型" : properties.model() + "模型";
    }

    /**
     * 标准化字符串值（去除首尾空格，空白字符串转为 null）
     *
     * @param value 原始字符串
     * @return 标准化后的字符串，如果为空或空白则返回 null
     */
    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
