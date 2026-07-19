package com.datamaster.app.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * When the native desktop shell supplies a per-launch token, only requests
 * coming through that shell may use the local analysis API. Browser-based
 * source development remains unchanged when the property is empty.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DesktopTokenFilter extends OncePerRequestFilter {
    private static final String HEADER = "X-DataMaster-Token";
    private final String expectedToken;

    public DesktopTokenFilter(@Value("${datamaster.desktop-token:}") String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken.strip();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (expectedToken.isEmpty() || !path.startsWith("/api/") || "/api/health".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String supplied = request.getHeader(HEADER);
        if (supplied != null && MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"桌面会话已失效，请重新启动 DataMaster\"}");
    }
}
