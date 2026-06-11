package com.mytext.learningassistant.admin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.mytext.learningassistant.common.ApiResponse;
import com.mytext.learningassistant.common.PageResponse;
import com.mytext.learningassistant.material.MaterialService;
import com.mytext.learningassistant.vector.VectorStoreClient;
import com.mytext.learningassistant.vector.VectorStoreProperties;

import jakarta.validation.Valid;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员后台控制器。
 * <p>
 * 提供管理员专用的 REST API 接口，包括：
 * <ul>
 *   <li>查看系统统计数据（用户数、资料数等）</li>
 *   <li>用户管理（分页查询、修改角色、修改状态）</li>
 *   <li>资料管理（分页查询、修改资料状态）</li>
 *   <li>系统日志查看</li>
 *   <li>使用记录查看</li>
 * </ul>
 * 所有接口都需要通过拦截器验证当前用户具有管理员角色才能访问。
 * </p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** 管理员业务服务，处理具体的业务逻辑 */
    private final AdminService adminService;
    private final MaterialService materialService;
    private final VectorStoreClient vectorStoreClient;
    private final VectorStoreProperties vectorStoreProperties;
    private final boolean ocrEnabled;
    private final String ocrCommand;
    private final boolean converterEnabled;
    private final String converterCommand;

    /**
     * 构造方法，通过 Spring 依赖注入 AdminService。
     *
     * @param adminService 管理员业务服务
     */
    public AdminController(
        AdminService adminService,
        MaterialService materialService,
        VectorStoreClient vectorStoreClient,
        VectorStoreProperties vectorStoreProperties,
        @Value("${app.ocr.enabled:false}") boolean ocrEnabled,
        @Value("${app.ocr.command:tesseract}") String ocrCommand,
        @Value("${app.document-preview.converter.enabled:true}") boolean converterEnabled,
        @Value("${app.document-preview.converter.command:soffice}") String converterCommand
    ) {
        this.adminService = adminService;
        this.materialService = materialService;
        this.vectorStoreClient = vectorStoreClient;
        this.vectorStoreProperties = vectorStoreProperties;
        this.ocrEnabled = ocrEnabled;
        this.ocrCommand = ocrCommand == null || ocrCommand.isBlank() ? "tesseract" : ocrCommand.trim();
        this.converterEnabled = converterEnabled;
        this.converterCommand = converterCommand == null || converterCommand.isBlank() ? "soffice" : converterCommand.trim();
    }

    /**
     * 获取系统统计数据。
     * <p>
     * 返回包括用户总数、资料总数、问答总数、收藏总数、日志总数等统计信息。
     * </p>
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器从 Token 中解析后放入请求属性）
     * @return 包含系统统计数据的统一响应
     */
    @GetMapping("/stats")
    public ApiResponse<AdminStatsResponse> stats(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(adminService.stats(currentUserId));
    }

    @GetMapping("/system/dependencies")
    public ApiResponse<List<SystemDependencyResponse>> dependencies(@RequestAttribute("currentUserId") long currentUserId) {
        adminService.requireAdminUser(currentUserId);
        List<SystemDependencyResponse> dependencies = new ArrayList<>();
        dependencies.add(checkCommand("pdfinfo", true, "pdfinfo"));
        dependencies.add(checkCommand("pdftotext", true, "pdftotext"));
        dependencies.add(checkCommand("pdftoppm", true, "pdftoppm"));
        dependencies.add(checkCommand("LibreOffice", converterEnabled, converterCommand));
        dependencies.add(checkCommand("Tesseract OCR", ocrEnabled, ocrCommand));
        dependencies.add(checkQdrant());
        return ApiResponse.ok(dependencies);
    }

    private SystemDependencyResponse checkCommand(String name, boolean enabled, String command) {
        if (!enabled) {
            return new SystemDependencyResponse(name, false, false, "配置未启用");
        }
        try {
            Process process = new ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start();
            boolean finished = process.waitFor(Duration.ofSeconds(3).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new SystemDependencyResponse(name, true, false, "命令超时：" + command);
            }
            return new SystemDependencyResponse(
                name,
                true,
                process.exitValue() == 0,
                process.exitValue() == 0 ? "可用：" + command : "命令返回非 0：" + command
            );
        } catch (Exception exception) {
            return new SystemDependencyResponse(name, true, false, "命令不可用：" + command);
        }
    }

    private SystemDependencyResponse checkQdrant() {
        if (!vectorStoreClient.configured()) {
            return new SystemDependencyResponse(
                "Qdrant",
                false,
                false,
                "向量库未启用或未配置，系统将只使用 MySQL embedding 和 BM25"
            );
        }
        try {
            String baseUrl = vectorStoreProperties.baseUrl();
            while (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(baseUrl + "/healthz"))
                .timeout(Duration.ofSeconds(3))
                .GET();
            if (!vectorStoreProperties.apiKey().isBlank()) {
                requestBuilder.header("api-key", vectorStoreProperties.apiKey());
            }
            HttpResponse<String> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
                .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            boolean healthy = response.statusCode() >= 200 && response.statusCode() < 300;
            return new SystemDependencyResponse(
                "Qdrant",
                true,
                healthy,
                healthy ? "可用：" + baseUrl : "Qdrant 返回状态码：" + response.statusCode()
            );
        } catch (Exception exception) {
            return new SystemDependencyResponse("Qdrant", true, false, "Qdrant 无法访问：" + vectorStoreProperties.baseUrl());
        }
    }

    /**
     * 分页查询用户列表。
     * <p>
     * 支持按关键词模糊搜索用户名、昵称等字段。
     * </p>
     *
     * @param currentUserId 当前登录管理员的用户 ID
     * @param keyword       搜索关键词（可选），用于模糊匹配用户名、昵称、角色、状态等
     * @param page          页码，从 0 开始，默认为 0
     * @param size          每页条数，默认为 20
     * @return 分页的用户列表响应
     */
    @GetMapping("/users")
    public ApiResponse<PageResponse<AdminUserResponse>> users(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.ok(adminService.users(currentUserId, keyword, page, size));
    }

    /**
     * 修改指定用户的角色。
     * <p>
     * 注意：不能移除自己的管理员角色，也不能将系统中最后一个管理员降级。
     * </p>
     *
     * @param currentUserId 当前登录管理员的用户 ID
     * @param id            要修改角色的目标用户 ID
     * @param request       请求体，包含新的角色值（如 "ADMIN"、"USER"）
     * @return 修改后的用户信息
     */
    @PatchMapping("/users/{id}/role")
    public ApiResponse<AdminUserResponse> updateUserRole(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @Valid @RequestBody AdminRoleUpdateRequest request
    ) {
        return ApiResponse.ok(adminService.updateUserRole(currentUserId, id, request.role()));
    }

    /**
     * 修改指定用户的账号状态。
     * <p>
     * 注意：管理员不能禁用自己当前登录的账号。
     * </p>
     *
     * @param currentUserId 当前登录管理员的用户 ID
     * @param id            要修改状态的目标用户 ID
     * @param request       请求体，包含新的状态值（如 "ACTIVE"、"DISABLED"）
     * @return 修改后的用户信息
     */
    @PatchMapping("/users/{id}/status")
    public ApiResponse<AdminUserResponse> updateUserStatus(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @RequestBody AdminUserStatusRequest request
    ) {
        return ApiResponse.ok(adminService.updateUserStatus(currentUserId, id, request.status()));
    }

    /**
     * 分页查询学习资料列表。
     * <p>
     * 支持按关键词模糊搜索资料标题、来源类型、解析状态等字段。
     * </p>
     *
     * @param currentUserId 当前登录管理员的用户 ID
     * @param keyword       搜索关键词（可选）
     * @param page          页码，从 0 开始，默认为 0
     * @param size          每页条数，默认为 20
     * @return 分页的资料列表响应
     */
    @GetMapping("/materials")
    public ApiResponse<PageResponse<AdminMaterialResponse>> materials(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.ok(adminService.materials(currentUserId, keyword, page, size));
    }

    /**
     * 修改指定学习资料的状态。
     * <p>
     * 可以同时修改解析状态和总结状态，但至少需要传入其中一个。
     * </p>
     *
     * @param currentUserId 当前登录管理员的用户 ID
     * @param id            要修改状态的资料 ID
     * @param request       请求体，包含 parseStatus（解析状态）和 summaryStatus（总结状态）
     * @return 修改后的资料信息
     */
    @PatchMapping("/materials/{id}/status")
    public ApiResponse<AdminMaterialResponse> updateMaterialStatus(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @RequestBody AdminMaterialStatusRequest request
    ) {
        return ApiResponse.ok(adminService.updateMaterialStatus(
            currentUserId,
            id,
            request.parseStatus(),
            request.summaryStatus()
        ));
    }

    /**
     * 管理员手动提交 Qdrant 向量索引重建任务。
     * <p>
     * 该接口不会重新上传、重新切片或重新 OCR，只会读取数据库中已有的资料片段，
     * 补齐缺失 Embedding，并异步写入 Qdrant。适合在首次启用 Qdrant 后对历史资料做回填。
     * </p>
     *
     * @param currentUserId 当前登录管理员的用户 ID
     * @param materialId    可选资料 ID；不传则提交所有已解析资料
     * @return 已提交后台任务数量
     */
    @PostMapping("/materials/vector-index/rebuild")
    public ApiResponse<AdminVectorIndexRebuildResponse> rebuildVectorIndex(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam(value = "materialId", required = false) Long materialId
    ) {
        adminService.requireAdminUser(currentUserId);
        int submitted = materialService.rebuildVectorIndexesForAdmin(materialId);
        String message = submitted == 0
            ? "没有可重建的已解析资料"
            : "已提交 " + submitted + " 个向量索引重建任务，后台会分批写入 Qdrant";
        return ApiResponse.ok(new AdminVectorIndexRebuildResponse(submitted, materialId, message));
    }

    /**
     * 分页查询系统操作日志。
     * <p>
     * 日志记录了管理员进行的各种操作（如修改用户角色、修改资料状态等）。
     * 不包含使用类日志（如 RAG 对话、上传资料等）。
     * </p>
     *
     * @param currentUserId 当前登录管理员的用户 ID
     * @param keyword       搜索关键词（可选），可匹配操作人、操作类型、目标类型、详情等
     * @param page          页码，从 0 开始，默认为 0
     * @param size          每页条数，默认为 20
     * @return 分页的系统日志列表响应
     */
    @GetMapping("/logs")
    public ApiResponse<PageResponse<SystemLogResponse>> logs(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.ok(adminService.logs(currentUserId, keyword, page, size));
    }

    /**
     * 分页查询使用记录。
     * <p>
     * 使用记录包括 RAG 对话、资料上传、问答记录等，用于统计 Token 用量和用户活跃度。
     * 数据来源包括 usage_record 表、系统日志中的使用类操作，以及 rag_question 表。
     * </p>
     *
     * @param currentUserId 当前登录管理员的用户 ID
     * @param keyword       搜索关键词（可选）
     * @param page          页码，从 0 开始，默认为 0
     * @param size          每页条数，默认为 20
     * @return 分页的使用记录列表响应
     */
    @GetMapping("/usage-records")
    public ApiResponse<PageResponse<UsageRecordResponse>> usageRecords(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.ok(adminService.usageRecords(currentUserId, keyword, page, size));
    }
}
