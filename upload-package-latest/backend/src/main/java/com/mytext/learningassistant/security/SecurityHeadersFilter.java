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

@Component
public class SecurityHeadersFilter implements Filter {

    private static final String CSP = String.join("; ",
        "default-src 'self'",
        "img-src 'self' data: blob:",
        "style-src 'self' 'unsafe-inline'",
        "script-src 'self'",
        "connect-src 'self' https: http:",
        "frame-ancestors 'self'"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
        throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            putIfMissing(httpResponse, "X-Content-Type-Options", "nosniff");
            putIfMissing(httpResponse, "Referrer-Policy", "strict-origin-when-cross-origin");
            putIfMissing(httpResponse, "X-Frame-Options", "SAMEORIGIN");
            putIfMissing(httpResponse, "Content-Security-Policy", CSP);
            if (request instanceof HttpServletRequest httpRequest && httpRequest.isSecure()) {
                putIfMissing(httpResponse, "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
            }
        }
        chain.doFilter(request, response);
    }

    private void putIfMissing(HttpServletResponse response, String name, String value) {
        if (response.getHeader(name) == null) {
            response.setHeader(name, value);
        }
    }
}
