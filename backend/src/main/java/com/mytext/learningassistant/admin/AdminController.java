package com.mytext.learningassistant.admin;

import com.mytext.learningassistant.common.ApiResponse;
import com.mytext.learningassistant.common.PageResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 构造方法，通过 Spring 依赖注入 AdminService。
     *
     * @param adminService 管理员业务服务
     */
    public AdminController(AdminService adminService) {
        this.adminService = adminService;
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
