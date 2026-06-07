package com.mytext.learningassistant.security;

import java.io.IOException;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;

/**
 * HTTP 安全响应头过滤器 —— 为所有 HTTP 响应自动添加安全相关的 HTTP 头，增强浏览器端的安全防护。
 *
 * <h3>添加的安全头说明</h3>
 * <table border="1">
 *   <tr><th>响应头</th><th>值</th><th>作用</th></tr>
 *   <tr>
 *     <td>{@code X-Content-Type-Options}</td>
 *     <td>{@code nosniff}</td>
 *     <td>禁止浏览器"猜测"MIME 类型。防止攻击者上传恶意文件（如含 JS 代码的 .txt）被浏览器当作脚本执行</td>
 *   </tr>
 *   <tr>
 *     <td>{@code Referrer-Policy}</td>
 *     <td>{@code strict-origin-when-cross-origin}</td>
 *     <td>跨域请求只发送来源域名（不带路径），同源请求发送完整 URL。防止敏感 URL 参数泄露给第三方</td>
 *   </tr>
 *   <tr>
 *     <td>{@code X-Frame-Options}</td>
 *     <td>{@code SAMEORIGIN}</td>
 *     <td>只允许同源页面通过 iframe 嵌入本应用。防止"点击劫持"（Clickjacking）攻击</td>
 *   </tr>
 *   <tr>
 *     <td>{@code Content-Security-Policy}</td>
 *     <td>（见下方 CSP 常量）</td>
 *     <td>限制页面能加载哪些资源（脚本、样式、图片等），是防御 XSS 攻击的核心手段</td>
 *   </tr>
 *   <tr>
 *     <td>{@code Strict-Transport-Security}</td>
 *     <td>{@code max-age=31536000; includeSubDomains}</td>
 *     <td>强制浏览器在 1 年内始终使用 HTTPS 访问（仅在 HTTPS 请求时添加）。防止中间人降级攻击</td>
 *   </tr>
 * </table>
 *
 * <h3>CSP（Content-Security-Policy）策略详解</h3>
 * <ul>
 *   <li>{@code default-src 'self'} —— 默认只允许加载同源资源</li>
 *   <li>{@code img-src 'self' data: blob:} —— 图片允许同源、data URI（Base64 图片）和 blob URL</li>
 *   <li>{@code style-src 'self' 'unsafe-inline'} —— 样式允许同源和行内样式（部分 UI 框架需要）</li>
 *   <li>{@code script-src 'self'} —— 脚本只允许同源，禁止内联脚本（重要 XSS 防线）</li>
 *   <li>{@code connect-src 'self' https: http:} —— AJAX/WebSocket 连接允许同源和任意 HTTP(S) 地址</li>
 *   <li>{@code frame-ancestors 'self'} —— 类似 X-Frame-Options，限制谁可以嵌入本页面</li>
 * </ul>
 *
 * <h3>设计特点</h3>
 * <p>
 * 使用 {@code putIfMissing} 策略：如果业务代码已经手动设置了某个安全头，
 * 过滤器不会覆盖它。这给予了业务层灵活的覆盖能力（比如某些接口需要更宽松的 CSP）。
 * </p>
 */
@Component
public class SecurityHeadersFilter implements Filter {

    /**
     * Content-Security-Policy 策略值。
     * <p>各指令用分号分隔，每个指令控制一类资源的加载策略。</p>
     */
    private static final String CSP = String.join("; ",
        "default-src 'self'",              // 默认策略：只允许加载同源资源
        "img-src 'self' data: blob:",      // 图片：允许同源 + Base64 内嵌 + blob URL
        "style-src 'self' 'unsafe-inline'", // 样式：允许同源 + 行内样式（兼容部分框架）
        "script-src 'self'",               // 脚本：严格限制为同源（核心 XSS 防线）
        "connect-src 'self' https: http:",  // 网络请求：允许同源和所有 HTTP(S) 地址
        "frame-ancestors 'self'"            // 嵌入限制：只允许同源页面嵌入（类似 X-Frame-Options）
    );

    /**
     * Servlet 过滤器的核心方法 —— 在每个 HTTP 请求/响应上添加安全头。
     *
     * @param request  Servlet 请求对象
     * @param response Servlet 响应对象
     * @param chain    过滤器链，调用 chain.doFilter 将请求传递给下一个过滤器或控制器
     * @throws IOException      IO 异常
     * @throws ServletException Servlet 异常
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        // 只对 HTTP 响应添加安全头（非 HTTP 协议直接跳过）
        if (response instanceof HttpServletResponse httpResponse) {
            putIfMissing(httpResponse, "X-Content-Type-Options", "nosniff");
            putIfMissing(httpResponse, "Referrer-Policy", "strict-origin-when-cross-origin");
            putIfMissing(httpResponse, "X-Frame-Options", "SAMEORIGIN");
            putIfMissing(httpResponse, "Content-Security-Policy", CSP);

            // HSTS 只在 HTTPS 连接时添加，因为它的作用就是告诉浏览器"以后都用 HTTPS 访问我"
            // 如果在 HTTP 连接时设置，攻击者可以在首次访问时剥离这个头，起不到保护作用
            if (request instanceof HttpServletRequest httpRequest && httpRequest.isSecure()) {
                putIfMissing(httpResponse, "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
        }
        // 将请求传递给过滤器链中的下一个组件
        chain.doFilter(request, response);
    }

    /**
     * 仅在响应头中不存在该头时才设置（避免覆盖业务代码的自定义值）。
     *
     * @param response HTTP 响应对象
     * @param name     响应头名称
     * @param value    响应头值
     */
    private void putIfMissing(HttpServletResponse response, String name, String value) {
        if (response.getHeader(name) == null) {
            response.setHeader(name, value);
        }
    }
}
