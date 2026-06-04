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
 * 认证拦截器 — 这是整个项目安全机制的核心。
 * <p>
 * 每个 /api/** 请求在到达 Controller 之前，都会先经过这个拦截器的 preHandle() 方法。
 * 它负责：
 * <ol>
 *   <li>判断请求是否需要登录</li>
 *   <li>从 Authorization 头中提取 Token</li>
 *   <li>验证 Token 是否有效（未被篡改、未过期）</li>
 *   <li>验证用户是否存在且状态正常</li>
 *   <li>管理员接口额外检查管理员权限</li>
 *   <li>把当前用户 ID 和角色放入 request，方便 Controller 使用</li>
 * </ol>
 * <p>
 * 如果任一检查失败，直接返回 401/403 JSON 响应，请求不会到达 Controller。
 * <p>
 * {@code @ConditionalOnBean(TokenService.class)} — 确保 TokenService 存在时才生效，
 * 避免测试环境因缺少配置而启动失败。
 */
@Component
@ConditionalOnBean(TokenService.class)
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * 公开路径白名单 — 这些路径不需要登录就能访问。
     * 包括：健康检查、LLM 状态、注册、登录、获取验证码、邮箱登录、重置密码、检查用户名。
     */
    private static final Set<String> PUBLIC_PATHS = Set.of(
        "/api/health",
        "/api/llm/status",
        "/api/auth/register",
        "/api/auth/login",
        "/api/auth/email-code",
        "/api/auth/email-login",
        "/api/auth/reset-password",
        "/api/auth/check-username"
    );

    private final TokenService tokenService;      // 用于解析和验证 Token
    private final ObjectMapper objectMapper;       // 用于将错误响应序列化为 JSON
    private final UserRepository userRepository;   // 用于查询用户是否存在

    public AuthInterceptor(TokenService tokenService, ObjectMapper objectMapper, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
    }

    /**
     * 请求拦截 — 在每个 API 请求处理前执行。
     * <p>
     * 检查流程：<ol>
     *   <li>OPTIONS 预检请求直接放行（浏览器 CORS 机制）</li>
     *   <li>公开路径直接放行</li>
     *   <li>带 ticket 参数的文件下载请求放行（通过临时票据认证）</li>
     *   <li>检查 Authorization: Bearer <token> 头</li>
     *   <li>解析 Token 获取 userId</li>
     *   <li>验证用户存在且状态为 ACTIVE</li>
     *   <li>管理员路径额外检查角色</li>
     *   <li>把 userId 和 role 存入 request 属性</li>
     * </ol>
     *
     * @param request  HTTP 请求对象
     * @param response HTTP 响应对象（用于写入错误信息）
     * @param handler  目标处理器（Controller 方法）
     * @return true=放行，false=拦截（请求不会到达 Controller）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String path = request.getRequestURI();

        // 1. OPTIONS 预检请求直接放行（浏览器在跨域请求前发送）
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        // 2. 公开路径不需要登录
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }

        // 3. 带临时票据（ticket）的文件下载请求放行（安全打开原文件功能）
        if (path.matches("^/api/materials/\\d+/file$") && request.getParameter("ticket") != null) {
            return true;
        }

        // 4. 检查 Authorization 头 — 格式必须为 "Bearer xxx"
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return unauthorized(response);  // 没有 Token 或格式不对
        }

        // 5. 提取 Token（去掉 "Bearer " 前缀，共7个字符）
        String token = header.substring(7).trim();
        try {
            // 解析 Token 获取 userId（同时验证签名和过期时间）
            long currentUserId = tokenService.parseUserId(token);

            // 6. 验证用户存在且状态正常
            UserEntity currentUser = userRepository.findById(currentUserId).orElse(null);
            if (currentUser == null || currentUser.getStatus() != UserStatus.ACTIVE) {
                return unauthorized(response);  // 用户不存在或已被禁用
            }

            // 7. 管理员路径额外检查 — 必须是 ADMIN 角色
            if (path.startsWith("/api/admin") && currentUser.getRole() != UserRole.ADMIN) {
                return forbidden(response);     // 权限不足
            }

            // 8. 把用户信息放入 request，Controller 通过 @RequestAttribute 获取
            request.setAttribute("currentUserId", currentUserId);
            request.setAttribute("currentUserRole", currentUser.getRole().name());
            return true;  // 全部检查通过，放行
        } catch (RuntimeException exception) {
            // Token 解析失败（无效、过期、被篡改等）
            return unauthorized(response);
        }
    }

    /** 返回 401 未登录响应（JSON 格式），并拦截请求 */
    private boolean unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());            // HTTP 401
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);     // 内容类型 JSON
        objectMapper.writeValue(response.getWriter(),
            ApiResponse.error(401, "未登录或登录已过期"));              // 错误信息
        return false;  // 拦截请求
    }

    /** 返回 403 权限不足响应（JSON 格式），并拦截请求 */
    private boolean forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());              // HTTP 403
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
            ApiResponse.error(403, "admin permission required"));
        return false;
    }
}
