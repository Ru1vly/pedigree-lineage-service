package com.edevlet.lineage.infrastructure.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Seeds traceId/spanId before authentication runs (registered as a plain, early servlet filter,
 * ahead of the Spring Security chain), so even 401/403 responses carry a trace ID. userId is
 * intentionally NOT set here: this filter runs before the JWT filter populates the security
 * context, so it would always be empty. userId is added to the MDC instead by
 * CustomJwtAuthenticationConverter, at the point a request is actually authenticated.
 */
@Component
@Order(-100)
public class TracingMdcFilter extends OncePerRequestFilter {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID = "spanId";
    public static final String MDC_TRANSACTION_ID = "transactionId";
    public static final String MDC_USER_ID = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String traceId = resolveTraceId(request);
            String spanId = resolveSpanId(request);

            MDC.put(MDC_TRACE_ID, traceId);
            MDC.put(MDC_SPAN_ID, spanId);
            response.setHeader("X-Trace-Id", traceId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String headerTraceId = request.getHeader("X-Trace-Id");
        if (headerTraceId != null && !headerTraceId.isBlank()) {
            return headerTraceId;
        }
        return UUID.randomUUID().toString();
    }

    private String resolveSpanId(HttpServletRequest request) {
        String headerSpanId = request.getHeader("X-Span-Id");
        if (headerSpanId != null && !headerSpanId.isBlank()) {
            return headerSpanId;
        }
        return UUID.randomUUID().toString().substring(0, 16);
    }
}
