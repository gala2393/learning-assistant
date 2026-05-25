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

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/stats")
    public ApiResponse<AdminStatsResponse> stats(@RequestAttribute("currentUserId") long currentUserId) {
        return ApiResponse.ok(adminService.stats(currentUserId));
    }

    @GetMapping("/users")
    public ApiResponse<PageResponse<AdminUserResponse>> users(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.ok(adminService.users(currentUserId, keyword, page, size));
    }

    @PatchMapping("/users/{id}/role")
    public ApiResponse<AdminUserResponse> updateUserRole(
        @RequestAttribute("currentUserId") long currentUserId,
        @PathVariable("id") long id,
        @Valid @RequestBody AdminRoleUpdateRequest request
    ) {
        return ApiResponse.ok(adminService.updateUserRole(currentUserId, id, request.role()));
    }

    @GetMapping("/materials")
    public ApiResponse<PageResponse<AdminMaterialResponse>> materials(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.ok(adminService.materials(currentUserId, keyword, page, size));
    }

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

    @GetMapping("/logs")
    public ApiResponse<PageResponse<SystemLogResponse>> logs(
        @RequestAttribute("currentUserId") long currentUserId,
        @RequestParam(value = "keyword", required = false) String keyword,
        @RequestParam(value = "page", defaultValue = "0") int page,
        @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        return ApiResponse.ok(adminService.logs(currentUserId, keyword, page, size));
    }
}
