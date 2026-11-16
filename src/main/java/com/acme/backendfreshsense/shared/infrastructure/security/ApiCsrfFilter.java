package com.acme.backendfreshsense.shared.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import java.io.IOException;
import java.util.Set;

final class ApiCsrfFilter extends OncePerRequestFilter {
    static final String HEADER = "X-FreshSense-CSRF";
    private static final String MARKER = "traceability";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final PathPatternRequestMatcher.Builder PATH = PathPatternRequestMatcher.withDefaults();
    private static final RequestMatcher API = PATH.matcher("/api/**");
    private static final RequestMatcher CREDENTIAL_ENTRY = new OrRequestMatcher(
            PATH.matcher(HttpMethod.POST, "/api/accounts/login"),
            PATH.matcher(HttpMethod.POST, "/api/accounts/register"));
    private static final RequestMatcher COOKIE_SESSION = new OrRequestMatcher(
            PATH.matcher(HttpMethod.POST, "/api/accounts/refresh"),
            PATH.matcher(HttpMethod.POST, "/api/accounts/logout"));
    static final RequestMatcher DEVICE_WRITES = new OrRequestMatcher(
            PATH.matcher(HttpMethod.POST, "/api/edge/claim"),
            PATH.matcher(HttpMethod.POST, "/api/edge/readings"),
            PATH.matcher(HttpMethod.POST, "/api/v1/sensor-readings"));

    private final CorsConfigurationSource cors;

    ApiCsrfFilter(CorsConfigurationSource cors) {
        this.cors = cors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE_METHODS.contains(request.getMethod()) || !API.matches(request) || DEVICE_WRITES.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean credentialEntry = CREDENTIAL_ENTRY.matches(request);
        boolean usesBrowserCredentials = credentialEntry || COOKIE_SESSION.matches(request)
                || !JwtAuthFilter.hasAuthenticatedBearer(request);
        if (usesBrowserCredentials && !isAllowedOrigin(request)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Origen no autorizado");
            return;
        }
        if (credentialEntry) {
            // A browser cannot send application/json cross-origin without passing CORS preflight.
            if (!hasJsonContentType(request)) {
                reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "Se requiere application/json");
                return;
            }
        } else if (usesBrowserCredentials && !hasMarker(request)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "Falta la cabecera de protección CSRF");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowedOrigin(HttpServletRequest request) {
        var values = request.getHeaders(HttpHeaders.ORIGIN);
        if (!values.hasMoreElements()) return true;
        String origin = values.nextElement();
        var configuration = cors.getCorsConfiguration(request);
        // Spring may treat an origin as same-origin using forwarded host information. Cookie writes
        // with an Origin use the explicit allowlist even on that path.
        return !values.hasMoreElements() && configuration != null && configuration.checkOrigin(origin) != null;
    }

    private static boolean hasJsonContentType(HttpServletRequest request) {
        var values = request.getHeaders(HttpHeaders.CONTENT_TYPE);
        if (!values.hasMoreElements()) return false;
        String header = values.nextElement();
        if (values.hasMoreElements()) return false;
        try {
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(MediaType.parseMediaType(header));
        } catch (InvalidMediaTypeException ex) {
            return false;
        }
    }

    private static boolean hasMarker(HttpServletRequest request) {
        var values = request.getHeaders(HEADER);
        return values.hasMoreElements() && MARKER.equals(values.nextElement()) && !values.hasMoreElements();
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"status\":" + status + ",\"message\":\"" + message + "\"}");
    }
}
