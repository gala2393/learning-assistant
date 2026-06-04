package com.mytext.learningassistant.config;

import java.util.Arrays;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS（跨域资源共享）配置。
 * <p>
 * 当浏览器中前端页面（如 http://localhost:5174）向后端（http://localhost:8080）
 * 发请求时，浏览器会先发一个 OPTIONS "预检请求"，检查后端是否允许跨域。
 * 这个配置告诉浏览器：哪些来源的请求是可以接受的。
 * <p>
 * 可跨域的前端地址通过 {@code app.cors.allowed-origins} 配置，多个用逗号分隔。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 允许的跨域来源数组（从配置文件解析） */
    private final String[] allowedOrigins;

    /**
     * 构造器 — 从配置文件读取 CORS 白名单。
     *
     * @param allowedOrigins 逗号分隔的来源列表，如 "http://localhost:5174,https://myapp.vercel.app"
     */
    public CorsConfig(
        @Value("${app.cors.allowed-origins:http://localhost:5173,http://localhost:5174,http://127.0.0.1:5173,http://127.0.0.1:5174,http://localhost:15174,http://127.0.0.1:15174}") String allowedOrigins
    ) {
        // 把逗号分隔的字符串拆成数组，过滤掉空白项
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isBlank())
            .toArray(String[]::new);
    }

    /**
     * 注册 CORS 映射规则。
     * <ul>
     *   <li>{@code /api/**} — 只对 API 路径开启跨域</li>
     *   <li>{@code allowedOriginPatterns} — 允许的来源（支持通配符如 https://*.vercel.app）</li>
     *   <li>{@code allowedMethods} — 允许的 HTTP 方法</li>
     *   <li>{@code allowCredentials(true)} — 允许携带 Cookie/Authorization 头</li>
     *   <li>{@code maxAge(3600)} — 预检请求缓存 1 小时（减少 OPTIONS 请求次数）</li>
     * </ul>
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(allowedOrigins)
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("*")
            .allowCredentials(true)
            .maxAge(3600);
    }
}
