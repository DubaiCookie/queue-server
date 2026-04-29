package com.skala.queue_server.filter;

import com.skala.queue_server.exception.ExpiredTokenException;
import com.skala.queue_server.exception.InvalidTokenException;
import com.skala.queue_server.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private static final String ACCESS_TOKEN_COOKIE_NAME = "ACCESS_TOKEN";
    private static final String AUTHENTICATED_USER_ID_ATTRIBUTE = "authenticatedUserId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        if ("OPTIONS".equalsIgnoreCase(method)) return true;
        if (path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")) return true;
        if (path.equals("/actuator/health")) return true;

        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String accessToken = extractTokenFromCookie(request);
            if (accessToken == null) {
                sendUnauthorizedResponse(response, "Access token not found");
                return;
            }
            jwtUtil.validateToken(accessToken);
            Long userId = jwtUtil.getUserIdFromToken(accessToken);
            request.setAttribute(AUTHENTICATED_USER_ID_ATTRIBUTE, userId);
            filterChain.doFilter(request, response);
        } catch (ExpiredTokenException e) {
            sendUnauthorizedResponse(response, "Access token has expired");
        } catch (InvalidTokenException e) {
            sendUnauthorizedResponse(response, "Invalid access token");
        } catch (Exception e) {
            sendUnauthorizedResponse(response, "Authentication error");
        }
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (ACCESS_TOKEN_COOKIE_NAME.equals(cookie.getName())) return cookie.getValue();
        }
        return null;
    }

    private void sendUnauthorizedResponse(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"error\": \"" + message + "\"}");
    }
}
