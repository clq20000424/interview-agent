package com.interview.agent.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器
 * - 从请求头 Authorization: Bearer <token> 提取 token
 * - 验证 token，失败返回 401
 * - token 过期返回 401 + 特定错误信息
 *
 * @author 陈龙强
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

        String token = authHeader.substring(7);
        try {
            String username = jwtService.validateToken(token);
            // 将用户名存入 request attribute，后续可使用
            request.setAttribute("username", username);
            filterChain.doFilter(request, response);
        } catch (JwtService.TokenExpiredException e) {
            log.warn("[Auth] Token 已过期: {}", e.getMessage());
            sendUnauthorized(response, "Token 已过期，请重新登录");
        } catch (Exception e) {
            log.warn("[Auth] Token 验证失败: {}", e.getMessage());
            sendUnauthorized(response, "Token 无效");
        }
    }

    /**
     * 判断是否跳过认证
     */
    private boolean shouldSkipAuth(String path) {
        return path.equals("/api/register")
                || path.equals("/api/login")
                || path.equals("/health")
                || path.startsWith("/ws");
    }

    /**
     * 返回 401 未授权响应
     */
    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // 添加 WWW-Authenticate 头，符合 HTTP 规范，避免 Jetty 等客户端报错
        response.setHeader("WWW-Authenticate", "Bearer realm=\"interview-agent\"");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\",\"code\":401}");
    }
}
