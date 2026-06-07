package com.mytext.learningassistant.auth;

import com.mytext.learningassistant.common.ApiResponse;
import com.mytext.learningassistant.security.RateLimitService;

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
 *   <li>POST /login-captcha — 创建登录验证码（当登录失败次数过多时触发）</li>
 *   <li>POST /email-code — 发送邮箱验证码</li>
 *   <li>POST /email-login — 邮箱验证码登录（未注册则自动创建）</li>
 *   <li>POST /reset-password — 通过邮箱验证码重置密码</li>
 *   <li>GET /check-username — 检查用户名是否可用</li>
 *   <li>GET /me — 获取当前登录用户信息</li>
 *   <li>PUT /me — 修改个人资料（昵称、头像）</li>
 *   <li>PUT /password — 修改密码（需提供原密码）</li>
 *   <li>POST /logout — 登出（后端使旧 Token 失效）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /** 认证业务服务，处理注册、登录等核心逻辑 */
    private final AuthService authService;

    /** 频率限制服务，防止恶意刷接口（如暴力破解密码、频繁发送验证码） */
    private final RateLimitService rateLimitService;

    /**
     * 构造方法，通过依赖注入获取所需的服务实例。
     *
     * @param authService     认证业务服务
     * @param rateLimitService 频率限制服务
     */
    public AuthController(AuthService authService, RateLimitService rateLimitService) {
        this.authService = authService;
        this.rateLimitService = rateLimitService;
    }

    /**
     * 注册新用户 — POST /api/auth/register
     *
     * @param request     注册请求体（包含用户名、密码、邮箱等），由 @Valid 自动校验
     * @param httpRequest HTTP 请求对象，用于获取客户端 IP 进行频率限制
     * @return 注册成功后的用户信息（不含密码）
     */
    @PostMapping("/register")
    public ApiResponse<AuthUserResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest httpRequest) {
        // 先检查该 IP 的注册频率是否超限
        rateLimitService.checkRegister(clientIp(httpRequest));
        return ApiResponse.ok(authService.register(request));
    }

    /**
     * 用户名/邮箱 + 密码登录 — POST /api/auth/login
     *
     * @param request     登录请求体（包含用户名、密码、可选验证码），由 @Valid 自动校验
     * @param httpRequest HTTP 请求对象，用于获取客户端 IP 进行频率限制
     * @return 登录成功后的 Token 和用户信息
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        String ipAddress = clientIp(httpRequest);
        // 频率限制 key = IP + 用户名，防止针对同一账号的暴力破解
        rateLimitService.checkLogin(ipAddress + ":" + request.username());
        return ApiResponse.ok(authService.login(request, ipAddress));
    }

    /**
     * 创建登录验证码 — POST /api/auth/login-captcha
     * 当用户登录失败次数过多时，前端调用此接口获取验证码，
     * 用户需要先完成验证码才能继续尝试登录。
     *
     * @param request     包含用户名的请求体
     * @param httpRequest HTTP 请求对象，用于获取客户端 IP
     * @return 验证码图片和挑战 ID
     */
    @PostMapping("/login-captcha")
    public ApiResponse<LoginCaptchaResponse> createLoginCaptcha(
        @Valid @RequestBody LoginCaptchaRequest request,
        HttpServletRequest httpRequest
    ) {
        String ipAddress = clientIp(httpRequest);
        // 频率限制 key = IP + 用户名 + captcha 标识
        rateLimitService.checkLogin(ipAddress + ":" + request.username() + ":captcha");
        return ApiResponse.ok(authService.createLoginCaptcha(request, ipAddress));
    }

    /**
     * 发送邮箱验证码 — POST /api/auth/email-code
     * 用于登录或重置密码场景，向指定邮箱发送一次性验证码。
     *
     * @param request     包含邮箱地址和邮件服务商的请求体，由 @Valid 自动校验
     * @param httpRequest HTTP 请求对象，用于获取客户端 IP 进行频率限制
     * @return 空数据的成功响应
     */
    @PostMapping("/email-code")
    public ApiResponse<Void> sendEmailCode(
        @Valid @RequestBody EmailCodeRequest request,
        HttpServletRequest httpRequest  // 需要获取客户端 IP 用于频率限制
    ) {
        // 频率限制 key = IP + 邮箱，防止同一 IP 对同一邮箱频繁发送验证码
        rateLimitService.checkEmailCode(clientIp(httpRequest) + ":" + request.email());
        authService.sendEmailCode(request, clientIp(httpRequest));
        return ApiResponse.ok(null);
    }

    /**
     * 邮箱验证码登录 — POST /api/auth/email-login
     * 如果该邮箱尚未注册，系统会自动创建一个新账号。
     *
     * @param request     包含邮箱和验证码的请求体，由 @Valid 自动校验
     * @param httpRequest HTTP 请求对象，用于获取客户端 IP 进行频率限制
     * @return 登录成功后的 Token 和用户信息
     */
    @PostMapping("/email-login")
    public ApiResponse<LoginResponse> emailLogin(@Valid @RequestBody EmailLoginRequest request, HttpServletRequest httpRequest) {
        rateLimitService.checkLogin(clientIp(httpRequest) + ":" + request.email());
        return ApiResponse.ok(authService.emailLogin(request));
    }

    /**
     * 通过邮箱验证码重置密码 — POST /api/auth/reset-password
     * 用户忘记密码时，通过邮箱验证身份后设置新密码。
     *
     * @param request     包含邮箱、验证码和新密码的请求体，由 @Valid 自动校验
     * @param httpRequest HTTP 请求对象，用于获取客户端 IP 进行频率限制
     * @return 空数据的成功响应
     */
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        rateLimitService.checkEmailCode(clientIp(httpRequest) + ":" + request.email());
        authService.resetPassword(request);
        return ApiResponse.ok(null);
    }

    /**
     * 检查用户名是否可用 — GET /api/auth/check-username?username=xxx
     *
     * @param username 要检查的用户名
     * @return 包含用户名、是否可用、提示信息的响应
     */
    @GetMapping("/check-username")
    public ApiResponse<UsernameAvailabilityResponse> checkUsername(@RequestParam("username") String username) {
        return ApiResponse.ok(authService.checkUsername(username));
    }

    /**
     * 获取当前登录用户信息 — GET /api/auth/me
     * currentUserId 由 AuthInterceptor 在拦截器中自动注入，前端不需要传递。
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器通过 request.setAttribute 注入）
     * @return 当前用户的详细信息
     */
    @GetMapping("/me")
    public ApiResponse<AuthUserResponse> me(
        @RequestAttribute("currentUserId") long currentUserId  // 由拦截器注入
    ) {
        return ApiResponse.ok(authService.me(currentUserId));
    }

    /**
     * 修改个人资料（昵称、头像）— PUT /api/auth/me
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器注入）
     * @param request       包含新昵称和头像的请求体，由 @Valid 自动校验
     * @return 更新后的用户信息
     */
    @PutMapping("/me")
    public ApiResponse<AuthUserResponse> updateMe(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ApiResponse.ok(authService.updateMe(currentUserId, request));
    }

    /**
     * 修改密码 — PUT /api/auth/password
     * 需要提供当前密码进行验证，修改成功后旧 Token 会自动失效。
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器注入）
     * @param request       包含当前密码、新密码和确认密码的请求体，由 @Valid 自动校验
     * @return 空数据的成功响应
     */
    @PutMapping("/password")
    public ApiResponse<Void> updatePassword(
        @RequestAttribute("currentUserId") long currentUserId,
        @Valid @RequestBody UpdatePasswordRequest request
    ) {
        authService.updatePassword(currentUserId, request);
        return ApiResponse.ok(null);
    }

    /**
     * 登出 — POST /api/auth/logout
     * 后端通过增加 tokenVersion 使当前 Token 失效，前端同时清除本地存储的 Token。
     *
     * @param currentUserId 当前登录用户的 ID（由拦截器注入）
     * @return 空数据的成功响应
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestAttribute("currentUserId") long currentUserId) {
        authService.logout(currentUserId);
        return ApiResponse.ok(null);
    }

    /**
     * 获取客户端的真实 IP 地址。
     * 考虑了反向代理的情况：先检查 X-Forwarded-For 头，再检查 X-Real-IP 头，
     * 最后才用 request.getRemoteAddr()。
     * <p>
     * X-Forwarded-For 可能包含多个 IP（格式：客户端IP, 代理1IP, 代理2IP），
     * 取第一个即客户端真实 IP。
     *
     * @param request HTTP 请求对象
     * @return 客户端的真实 IP 地址字符串
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
        // 直连场景（没有经过代理）
        return request.getRemoteAddr();
    }
}
