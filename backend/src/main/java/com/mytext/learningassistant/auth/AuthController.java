package com.mytext.learningassistant.auth;

import com.mytext.learningassistant.common.ApiResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口控制器 — 处理所有与用户身份相关的 HTTP 请求。
 * <p>
 * 所有接口路径都以 /api/auth 开头，由 {@link AuthService} 处理实际的业务逻辑。
 * <p>
 * 接口摘要：
 * <ul>
 *   <li>POST /register — 注册新用户</li>
 *   <li>POST /login — 用户名密码登录</li>
 *   <li>POST /email-code — 发送邮箱验证码</li>
 *   <li>POST /email-login — 邮箱验证码登录（未注册则自动创建）</li>
 *   <li>POST /reset-password — 通过邮箱验证码重置密码</li>
 *   <li>GET /check-username — 检查用户名是否可用</li>
 *   <li>GET /me — 获取当前登录用户信息</li>
 *   <li>PUT /me — 修改个人资料（昵称、头像）</li>
 *   <li>PUT /password — 修改密码（需提供原密码）</li>
 *   <li>POST /logout — 登出（当前仅返回成功）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册新用户 — POST /api/auth/register */
    @PostMapping("/register")
    public ApiResponse<AuthUserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    /** 用户名/邮箱 + 密码登录 — POST /api/auth/login，返回 Token 和用户信息 */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    /** 发送邮箱验证码 — POST /api/auth/email-code，用于登录或重置密码 */
    @PostMapping("/email-code")
    public ApiResponse<Void> sendEmailCode(
        @Valid @RequestBody EmailCodeRequest request,
        HttpServletRequest httpRequest  // 需要获取客户端 IP 用于频率限制
    ) {
        authService.sendEmailCode(request, clientIp(httpRequest));
        return ApiResponse.ok(null);
    }

    /** 邮箱验证码登录 — POST /api/auth/email-login，邮箱未注册则自动创建账号 */
    @PostMapping("/email-login")
    public ApiResponse<LoginResponse> emailLogin(@Valid @RequestBody EmailLoginRequest request) {
        return ApiResponse.ok(authService.emailLogin(request));
    }

    /** 通过邮箱验证码重置密码 — POST /api/auth/reset-password */
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.ok(null);
    }

    /** 检查用户名是否可用 — GET /api/auth/check-username?username=xxx */
    @GetMapping("/check-username")
    public ApiResponse<UsernameAvailabilityResponse> checkUsername(@RequestParam("username") String username) {
        return ApiResponse.ok(authService.checkUsername(username));
    }

    /**
     * 获取当前登录用户信息 — GET /api/auth/me。
     * currentUserId 由 AuthInterceptor 在拦截器中注入，不需要前端传递。
     */
    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me(
        @RequestAttribute("currentUserId") long currentUserId  // 由拦截器注入
    ) {
        return ApiResponse.ok(authService.me(currentUserId));
    }

    /** 修改个人资料（昵称、头像）— PUT /api/auth/me */
    @PutMapping("/me")
    public ApiResponse<AuthUserResponse> updateMe(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ApiResponse.ok(authService.updateMe(currentUserId, request));
    }

    /** 修改密码 — PUT /api/auth/password，需要提供当前密码 */
    @PutMapping("/password")
    public ApiResponse<Void> updatePassword(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody UpdatePasswordRequest request
    ) {
        authService.updatePassword(currentUserId, request);
        return ApiResponse.ok(null);
    }

    /** 登出 — POST /api/auth/logout（当前只需在前端清除 Token 即可） */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        return ApiResponse.ok(null);
    }

    /**
     * 获取客户端的真实 IP 地址。
     * 考虑了反向代理的情况：先检查 X-Forwarded-For 头，再检查 X-Real-IP 头，
     * 最后才用 request.getRemoteAddr()。
     * <p>
     * X-Forwarded-For 可能包含多个 IP（格式：客户端IP, 代理1IP, 代理2IP），
     * 取第一个即客户端真实 IP。
     */
    private String clientIp(HttpServletRequest request) {
        // 反向代理场景：Nginx 等会设置这个头
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",", 2)[0].trim();  // 取第一个 IP
        }
        // 某些代理用 X-Real-IP
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        // 直连场景
        return request.getRemoteAddr();
    }
}
