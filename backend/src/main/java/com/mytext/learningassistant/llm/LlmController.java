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

@RestController
@RequestMapping("/api/llm")
public class LlmController {

    private final LlmProperties properties;
    private final UserLlmConfigRepository userLlmConfigRepository;
    private final ThirdPartyLlmClient thirdPartyLlmClient;

    public LlmController(
        LlmProperties properties,
        UserLlmConfigRepository userLlmConfigRepository,
        ThirdPartyLlmClient thirdPartyLlmClient
    ) {
        this.properties = properties;
        this.userLlmConfigRepository = userLlmConfigRepository;
        this.thirdPartyLlmClient = thirdPartyLlmClient;
    }

    @GetMapping("/status")
    public ApiResponse<LlmStatusResponse> status() {
        boolean configured = properties.configured();
        String message = configured
            ? "大模型已配置，智能问答会优先调用第三方模型。"
            : "大模型未配置完整，系统会使用本地 RAG 兜底答案。";
        return ApiResponse.ok(new LlmStatusResponse(
            properties.enabled(),
            configured,
            message
        ));
    }

    @GetMapping("/user-config")
    public ApiResponse<UserLlmConfigResponse> userConfig(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(toResponse(currentUserId));
    }

    @PutMapping("/user-config")
    @Transactional
    public ApiResponse<UserLlmConfigResponse> saveUserConfig(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestBody UserLlmConfigRequest request
    ) {
        if (!request.enabled()) {
            deactivateUserConfigs(currentUserId);
            return ApiResponse.ok(toResponse(currentUserId));
        }

        UserLlmConfigEntity config = request.id() == null
            ? new UserLlmConfigEntity()
            : userLlmConfigRepository.findByIdAndUserId(request.id(), currentUserId)
                .orElseThrow(() -> new BusinessException(404, "模型配置不存在"));
        config.setUserId(currentUserId);
        config.setEnabled(true);
        config.setActive(false);
        config.setDisplayName(displayName(request.displayName(), request.model()));
        config.setBaseUrl(normalize(request.baseUrl()));
        config.setModel(normalize(request.model()));
        String apiKey = normalize(request.apiKey());
        if (apiKey != null) {
            config.setApiKey(apiKey);
        }
        if (request.enabled() && !configured(config)) {
            throw new BusinessException(400, "请填写 URL 地址、密钥和模型名称");
        }
        deactivateUserConfigs(currentUserId);
        config.setActive(true);
        userLlmConfigRepository.save(config);
        return ApiResponse.ok(toResponse(currentUserId));
    }

    @PostMapping("/user-config/test")
    public ApiResponse<UserLlmTestResponse> testUserConfig(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestBody UserLlmConfigRequest request
    ) {
        UserLlmConfigEntity existing = request.id() == null
            ? userLlmConfigRepository.findFirstByUserIdAndActiveTrueOrderByUpdatedAtDesc(currentUserId).orElse(null)
            : userLlmConfigRepository.findByIdAndUserId(request.id(), currentUserId).orElse(null);
        String baseUrl = normalize(request.baseUrl());
        String apiKey = normalize(request.apiKey());
        String model = normalize(request.model());
        if (apiKey == null && existing != null) {
            apiKey = existing.getApiKey();
        }
        if (baseUrl == null || apiKey == null || model == null) {
            throw new BusinessException(400, "请填写 URL 地址、密钥和模型名称");
        }
        return ApiResponse.ok(thirdPartyLlmClient.testUserConfig(baseUrl, apiKey, model)
            .map(result -> new UserLlmTestResponse(true, "连通性测试成功", result.modelName()))
            .orElseGet(() -> new UserLlmTestResponse(false, "连通性测试失败，请检查 URL、密钥和模型名称", model)));
    }

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

    private void deactivateUserConfigs(long userId) {
        var configs = userLlmConfigRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        for (UserLlmConfigEntity config : configs) {
            if (config.isActive()) {
                config.setActive(false);
                userLlmConfigRepository.save(config);
            }
        }
    }

    private boolean configured(UserLlmConfigEntity config) {
        return config.getBaseUrl() != null && !config.getBaseUrl().isBlank()
            && config.getApiKey() != null && !config.getApiKey().isBlank()
            && config.getModel() != null && !config.getModel().isBlank();
    }

    private String displayName(String displayName, String model) {
        String value = normalize(displayName);
        if (value != null) {
            return value;
        }
        value = normalize(model);
        return value == null ? "自定义模型" : value;
    }

    private String label(UserLlmConfigEntity config) {
        String displayName = normalize(config.getDisplayName());
        return displayName == null ? displayName(null, config.getModel()) : displayName;
    }

    private String systemModelLabel() {
        return properties.model() == null || properties.model().isBlank() ? "gpt5.5模型" : properties.model() + "模型";
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
