package com.edevlet.lineage.infrastructure.ratelimit;

import com.edevlet.lineage.dto.ErrorResponse;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Per-user ingress rate limiting, counted in Redis so the limit is the limit no matter how many
 * replicas are running.
 *
 * <p>This filter previously counted in an in-JVM Resilience4j {@code RateLimiterRegistry}. Each
 * pod then enforced its own private 10-per-minute allowance, so the advertised limit was really
 * 10xN - between 20 and 120 requests a minute across the 2..12 replicas KEDA scales this service
 * to, varying with the current replica count and with whichever pod the load balancer happened to
 * pick. A limit that moves when you scale is not a limit. The registry also keyed limiters by
 * userId and never evicted them, so it grew by one limiter per distinct caller for the lifetime of
 * the process. Redis was already a required dependency of this service (distributed lock, state
 * cache), so there was nothing to add to the stack.
 *
 * <p>The window is a fixed window anchored at the caller's first request rather than at a wall
 * clock boundary: replicas do not need synchronised clocks for the count to be correct, because
 * the TTL that defines the window is set by Redis itself. Expiry is also what evicts the counter -
 * there is no unbounded map anywhere.
 *
 * <p><b>Behaviour when Redis is unreachable: fail open.</b> Rate limiting protects the census
 * backend from overload; it is not an authorization control, and no part of this service's
 * security model rests on it. Denying every citizen's request because the counter store is down
 * would convert a Redis blip into a full outage of a public service. The failure is logged at WARN
 * so it is visible rather than silent.
 *
 * <p>Registered explicitly in SecurityConfig's filter chain, after JWT authentication, so
 * {@link UserSecurityContextHolder} is actually populated when this filter runs and per-user
 * limits are possible. It is intentionally not a {@code @Component}: SecurityConfig disables
 * Boot's automatic servlet-filter registration for this bean so it only ever runs once, at the
 * point the security chain inserts it, rather than a second time earlier and unauthenticated.
 *
 * <p>The 429 response is written directly here rather than by throwing and letting
 * GlobalExceptionHandler translate it: {@code @RestControllerAdvice} only intercepts exceptions
 * thrown during DispatcherServlet's invocation of a controller method, not exceptions thrown by
 * a servlet filter upstream of it - an exception thrown from here would otherwise surface as an
 * unhandled 500, not the intended 429.
 */
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    public static final String KEY_PREFIX = "ratelimit:lineage:ingress:";

    /**
     * INCR then, only on the first hit of a window, PEXPIRE. Both run inside one Redis script so
     * two replicas incrementing concurrently cannot interleave into a counter that never expires.
     * Returns the post-increment count and the window's remaining milliseconds, so an accurate
     * Retry-After can be sent without a second round trip.
     */
    private static final RedisScript<List> INCREMENT_AND_EXPIRE = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then " +
            "  redis.call('PEXPIRE', KEYS[1], ARGV[1]) " +
            "end " +
            "return {current, redis.call('PTTL', KEYS[1])}",
            List.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final int limitForPeriod;
    private final Duration refreshPeriod;

    public RateLimitingFilter(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            int limitForPeriod,
            Duration refreshPeriod) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.limitForPeriod = limitForPeriod;
        this.refreshPeriod = refreshPeriod;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (isQuerySubmissionRequest(request)) {
            String clientKey = extractClientKey(request);
            RateLimitDecision decision = consumePermit(clientKey);

            if (!decision.allowed()) {
                writeRateLimitResponse(response, request, clientKey, decision.retryAfterSeconds());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private RateLimitDecision consumePermit(String clientKey) {
        try {
            List<?> result = redisTemplate.execute(
                    INCREMENT_AND_EXPIRE,
                    List.of(KEY_PREFIX + clientKey),
                    String.valueOf(refreshPeriod.toMillis()));

            if (result == null || result.size() < 2) {
                log.warn("Rate limit script returned no usable result for clientKey={}; allowing the request.", clientKey);
                return RateLimitDecision.permit();
            }

            long count = ((Number) result.get(0)).longValue();
            long remainingMillis = ((Number) result.get(1)).longValue();

            if (count > limitForPeriod) {
                return RateLimitDecision.deny(retryAfterSeconds(remainingMillis));
            }
            return RateLimitDecision.permit();

        } catch (Exception redisFailure) {
            // Fail open - see the class comment. A counter store outage must not take the service
            // down with it.
            log.warn("Rate limit counter unavailable for clientKey={}; allowing the request. Cause: {}",
                    clientKey, redisFailure.getMessage());
            return RateLimitDecision.permit();
        }
    }

    private long retryAfterSeconds(long remainingMillis) {
        if (remainingMillis <= 0) {
            // -1 (no TTL) or -2 (key gone) between the INCR and the PTTL; the window is at most
            // refreshPeriod long, so that is the honest upper bound to advertise.
            return Math.max(1, refreshPeriod.toSeconds());
        }
        return Math.max(1, (remainingMillis + 999) / 1000);
    }

    private boolean isQuerySubmissionRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && request.getRequestURI().endsWith("/api/v1/lineage/queries");
    }

    private void writeRateLimitResponse(
            HttpServletResponse response, HttpServletRequest request, String clientKey, long retryAfterSeconds)
            throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
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

    private record RateLimitDecision(boolean allowed, long retryAfterSeconds) {
        static RateLimitDecision permit() {
            return new RateLimitDecision(true, 0);
        }

        static RateLimitDecision deny(long retryAfterSeconds) {
            return new RateLimitDecision(false, retryAfterSeconds);
        }
    }
}
