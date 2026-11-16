package com.acme.backendfreshsense.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final String AUTHENTICATED_BEARER = JwtAuthFilter.class.getName() + ".authenticatedBearer";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String token = extractTokenFromHeader(request);
        boolean bearer = token != null;

        if (token == null) {
            token = extractTokenFromCookie(request);
        }

        if (token != null && jwtService.isValid(token) && !"refresh".equals(jwtService.extractType(token))) {
            String email = jwtService.extractEmail(token);
            String role = jwtService.extractRole(token);
            Long userId = jwtService.extractUserId(token);
            String grantedRole = (role != null) ? "ROLE_" + role : "ROLE_USER";

            var auth = new UsernamePasswordAuthenticationToken(
                    email, null, List.of(new SimpleGrantedAuthority(grantedRole)));
            auth.setDetails(userId);
            SecurityContextHolder.getContext().setAuthentication(auth);
            if (bearer) request.setAttribute(AUTHENTICATED_BEARER, true);
        }

        chain.doFilter(request, response);
    }

    static boolean hasAuthenticatedBearer(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(AUTHENTICATED_BEARER));
    }

    private String extractTokenFromHeader(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    private String extractTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> "authToken".equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
