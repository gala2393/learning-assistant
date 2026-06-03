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

@Component
@ConditionalOnBean(TokenService.class)
public class AuthInterceptor implements HandlerInterceptor {

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

    private final TokenService tokenService;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;

    public AuthInterceptor(TokenService tokenService, ObjectMapper objectMapper, UserRepository userRepository) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.userRepository = userRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String path = request.getRequestURI();
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        if (path.matches("^/api/materials/\\d+/file$") && request.getParameter("ticket") != null) {
            return true;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            return unauthorized(response);
        }

        String token = header.substring(7).trim();
        try {
            long currentUserId = tokenService.parseUserId(token);
            UserEntity currentUser = userRepository.findById(currentUserId).orElse(null);
            if (currentUser == null || currentUser.getStatus() != UserStatus.ACTIVE) {
                return unauthorized(response);
            }
            if (path.startsWith("/api/admin") && currentUser.getRole() != UserRole.ADMIN) {
                return forbidden(response);
            }
            request.setAttribute("currentUserId", currentUserId);
            request.setAttribute("currentUserRole", currentUser.getRole().name());
            return true;
        } catch (RuntimeException exception) {
            return unauthorized(response);
        }
    }

    private boolean unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(401, "未登录或登录已过期"));
        return false;
    }

    private boolean forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error(403, "admin permission required"));
        return false;
    }
}
