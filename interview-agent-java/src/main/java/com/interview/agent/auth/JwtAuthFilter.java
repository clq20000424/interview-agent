package com.interview.agent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * JWT authentication filter.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // 跳过不需要认证的路径
        if (shouldSkipAuth(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendUnauthorized(response, "缺少认证信息");
            return;
        }

        String username;
        try {
            username = jwtService.validateToken(authHeader.substring(7));
        } catch (JwtService.TokenExpiredException e) {
            log.warn("[Auth] Token expired: {}", e.getMessage());
            sendUnauthorized(response, "Token 已过期，请重新登录");
            return;
        } catch (Exception e) {
            log.warn("[Auth] Token validation failed: {}", e.getMessage());
            sendUnauthorized(response, "Token 无效");
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(username, null, Collections.emptyList());
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        request.setAttribute("username", username);
        filterChain.doFilter(request, response);
    }

    private boolean shouldSkipAuth(String path) {
        return path.equals("/api/register")
                || path.equals("/api/login")
                || path.equals("/health")
                || path.startsWith("/ws");
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"interview-agent\"");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":401}");
    }
}
