package com.edevlet.lineage.infrastructure.ratelimit;

import com.edevlet.lineage.dto.ErrorResponse;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Registered explicitly in SecurityConfig's filter chain, after JWT authentication, so
 * {@link UserSecurityContextHolder} is actually populated when this filter runs and per-user
 * limits are possible. It is intentionally not a {@code @Component}: SecurityConfig disables
 * Boot's automatic servlet-filter registration for this bean so it only ever runs once, at the
 * point the security chain inserts it, rather than a second time earlier and unauthenticated.
 * <p>
 * The 429 response is written directly here rather than by throwing and letting
 * GlobalExceptionHandler translate it: {@code @RestControllerAdvice} only intercepts exceptions
 * thrown during DispatcherServlet's invocation of a controller method, not exceptions thrown by
 * a servlet filter upstream of it - an exception thrown from here would otherwise surface as an
 * unhandled 500, not the intended 429.
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String RATE_LIMITER_CONFIG_NAME = "lineageIngress";

    private final RateLimiterRegistry rateLimiterRegistry;
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(RateLimiterRegistry rateLimiterRegistry, ObjectMapper objectMapper) {
        this.rateLimiterRegistry = rateLimiterRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (isQuerySubmissionRequest(request)) {
            String clientKey = extractClientKey(request);
            RateLimiter limiter = rateLimiterRegistry.rateLimiter(clientKey, RATE_LIMITER_CONFIG_NAME);

            if (!limiter.acquirePermission()) {
                writeRateLimitResponse(response, request, clientKey);
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isQuerySubmissionRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().endsWith("/api/v1/lineage/queries");
    }

    private void writeRateLimitResponse(HttpServletResponse response, HttpServletRequest request, String clientKey) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", "60");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(429)
                .error("Too Many Requests")
                .errorCode("RATE_LIMIT_EXCEEDED")
                .message("Rate limit exceeded for client: " + clientKey + ". Please try again later.")
                .path(request.getRequestURI())
                .traceId(MDC.get("traceId"))
                .build();

        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private String extractClientKey(HttpServletRequest request) {
        // This endpoint always requires authentication (see SecurityConfig), so by the time this
        // filter runs post-auth, a security context is expected to be present; the IP fallback
        // below only guards against a future permitAll endpoint being rate-limited by mistake.
        return UserSecurityContextHolder.getContext()
                .map(identityContext -> "user:" + identityContext.userId())
                .orElseGet(() -> "ip:" + request.getRemoteAddr());
    }
}
