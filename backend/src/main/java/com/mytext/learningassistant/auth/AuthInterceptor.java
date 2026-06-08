package com.mytext.learningassistant.auth;

import java.io.IOException;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytext.learningassistant.common.ApiResponse;
import com.mytext.learningassistant.user.UserEntity;
import com.mytext.learningassistant.user.UserRepository;
import com.mytext.learningassistant.user.UserRole;
import com.mytext.learningassistant.user.UserStatus;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器 — 在每个请求到达 Controller 之前进行身份验证和权限检查。
 * <p>
 * 工作流程：
 * <ol>
 *   <li>检查请求路径是否在公开路径列表中（如登录、注册），是则直接放行</li>
 *   <li>检查是否为 CORS 预检请求（OPTIONS 方法），是则直接放行</li>
 *   <li>检查是否为带 ticket 参数的材料文件下载/预览请求，是则直接放行</li>
 *   <li>从 Authorization 请求头中提取 Bearer Token 并解析验证</li>
 *   <li>检查用户是否存在且状态为正常（ACTIVE）</li>
 *   <li>检查 Token 版本号是否与数据库中的一致（防止旧 Token 被滥用）</li>
 *   <li>如果是管理后台接口（/api/admin/**），还需检查用户角色是否为 ADMIN</li>
 *   <li>验证通过后，将 currentUserId 和 currentUserRole 注入到 request 属性中，供后续 Controller 使用</li>
 * </ol>
 * <p>
 * 仅在 TokenService Bean 存在时才启用（通过 {@code @ConditionalOnBean} 控制）。
 */
@Component
@ConditionalOnBean(TokenService.class)
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * 公开路径列表 — 这些接口不需要登录认证即可访问。
     * 包括健康检查、登录、注册、邮箱验证、重置密码等。
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/health",              // 健康检查
        "/api/llm/status",          // LLM 服务状态
        "/api/auth/register",       // 用户注册
        "/api/auth/login",          // 用户名密码登录
        "/api/auth/login-captcha",  // 登录验证码
        "/api/auth/email-code",     // 发送邮箱验证码
        "/api/auth/email-login",    // 邮箱验证码登录
        "/api/auth/reset-password", // 重置密码
        "/api/auth/check-username"  // 检查用户名是否可用
    );

    /** Token 解析和验证服务 */
    private final TokenService tokenService;

    /** JSON 序列化工具，用于将错误响应写入 HTTP 输出流 */
    private final ObjectMapper objectMapper;

    /** 用户数据库仓库，用于查询用户信息 */
    private final UserRepository userRepository;

    /**
     * 构造方法，通过依赖注入获取所需组件。
     *
     * @param tokenService   Token 解析和验证服务
     * @param objectMapper   JSON 序列化工具
     * @param userRepository 用户数据库仓库
     */
    public AuthInterceptor(TokenService tokenService, ObjectMapper objectMapper, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
    }

    /**
     * 请求预处理方法 — 在请求到达 Controller 之前执行。
     * 返回 true 表示放行，返回 false 表示拦截（已自行写入错误响应）。
     *
     * @param request  HTTP 请求对象
     * @param response HTTP 响应对象
     * @param handler  目标处理器
     * @return true 放行，false 拦截
     * @throws IOException 写入响应时可能抛出的 IO 异常
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String path = request.getRequestURI();

        // CORS 预检请求（OPTIONS）和公开路径直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod()) || PUBLIC_PATHS.contains(path)) {
            return true;
        }

        // 带 ticket 参数的材料文件下载/预览请求直接放行（ticket 由 MaterialFileTicketService 单独验证）
        if (path.matches("^/api/materials/\\d+/(?:file|preview-file)$") && request.getParameter("ticket") != null) {
            // 文件临时 ticket 与 Bearer Token 二选一，便于浏览器直接预览受保护文件。
            return true;
        }

        // 从 Authorization 请求头中提取 Bearer Token
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return unauthorized(response);  // 没有 Token 或格式不正确
        }

        String token = header.substring(7).trim();  // 去掉 "Bearer " 前缀
        try {
            // 解析 Token 中的用户 ID 和版本号
            TokenService.TokenClaims claims = tokenService.parseClaims(token);
            long currentUserId = claims.userId();

            // 从数据库查询用户，检查用户是否存在且状态为正常
            UserEntity currentUser = userRepository.findById(currentUserId).orElse(null);
            if (currentUser == null || currentUser.getStatus() != UserStatus.ACTIVE) {
                return unauthorized(response);  // 用户不存在或已被禁用
            }
            // Token 版本号不匹配，说明该 Token 已被废弃（如用户修改了密码或登出）
            if (currentUser.getTokenVersion() != claims.tokenVersion()) {
                // tokenVersion 是服务端主动失效旧 Token 的边界，必须和数据库当前值一致。
                return unauthorized(response);
            }
            // 管理后台接口需要 ADMIN 角色才能访问
            if (path.startsWith("/api/admin") && currentUser.getRole() != UserRole.ADMIN) {
                return forbidden(response);  // 权限不足
            }

            // 验证通过，将用户信息注入到 request 属性中，供后续 Controller 使用
            request.setAttribute("currentUserId", currentUserId);
            request.setAttribute("currentUserRole", currentUser.getRole().name());
            return true;  // 放行
        } catch (RuntimeException exception) {
            // Token 解析失败（格式错误、已过期等）
            return unauthorized(response);
        }
    }

    /**
     * 返回 401 未授权响应。
     * 当 Token 缺失、无效、过期、用户不存在或已被禁用时调用。
     *
     * @param response HTTP 响应对象
     * @return 始终返回 false，表示拦截请求
     * @throws IOException 写入响应时可能抛出的 IO 异常
     */
    private boolean unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(401, "unauthorized"));
        return false;
    }

    /**
     * 返回 403 禁止访问响应。
     * 当用户已登录但权限不足（如普通用户访问管理后台接口）时调用。
     *
     * @param response HTTP 响应对象
     * @return 始终返回 false，表示拦截请求
     * @throws IOException 写入响应时可能抛出的 IO 异常
     */
    private boolean forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(403, "admin permission required"));
        return false;
    }
}
