package com.mytext.learningassistant.config;

import com.mytext.learningassistant.auth.AuthInterceptor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置 — 注册认证拦截器。
 * <p>
 * 把 AuthInterceptor 注册到 Spring MVC 的拦截器链中，拦截所有 /api/** 请求，
 * 在请求到达 Controller 之前检查 Token 是否有效。
 * <p>
 * {@code @ConditionalOnBean(AuthInterceptor.class)} 确保只有当 AuthInterceptor
 * 存在时才注册（避免测试环境因缺少 TokenService 而启动失败）。
 */
@Configuration
@ConditionalOnBean(AuthInterceptor.class)  // 只有 AuthInterceptor Bean 存在时才生效
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    /**
     * 注册拦截器 — 所有 /api/** 请求都会先经过 AuthInterceptor.preHandle()。
     * 如果 preHandle 返回 false（Token 无效），请求被拒绝，不会到达 Controller。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
            .addPathPatterns("/api/**");  // 拦截所有 API 路径
    }
}
