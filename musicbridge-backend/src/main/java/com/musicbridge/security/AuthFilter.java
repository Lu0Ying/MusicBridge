package com.musicbridge.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.musicbridge.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 最小 Bearer 会话认证过滤器。
 *
 * <p>只信任 Authorization 头解析出的身份；userId 不来自请求参数或请求体。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public AuthFilter(SessionService sessionService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || "/api/v1/auth/login".equals(path)
                || "/health".equals(path)
                || "/ready".equals(path)
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || "/error".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        Optional<AuthContext> context = Optional.empty();
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            context = sessionService.authenticate(header.substring(BEARER_PREFIX.length()).trim());
        }
        if (context.isEmpty()) {
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(AuthContext.REQUEST_ATTRIBUTE, context.get());
        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(),
                new ApiError("UNAUTHENTICATED", "缺少或无效的会话令牌", null));
    }
}
