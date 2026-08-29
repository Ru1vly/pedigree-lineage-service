package com.edevlet.lineage.infrastructure.security;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps the caller's origin IP and user agent onto the authenticated
 * {@link NationalIdentityContext}.
 *
 * <p>{@link CustomJwtAuthenticationConverter} cannot do this itself: it is a
 * {@code Converter<Jwt, ...>} and never sees the HttpServletRequest, so it passed {@code null} for
 * both fields. Nothing else filled them in, which meant
 * {@code lineage_audit_logs.ip_address} was empty for every row written on the HTTP path, and the
 * worker - having nothing to propagate - stamped the literal {@code "SYSTEM_KAFKA_WORKER"} on the
 * asynchronous path. For a service whose admin controller is labelled a compliance audit trail,
 * the origin of a request is not an optional field.
 *
 * <p>Runs inside the security chain, after authentication, so there is a context to enrich. The
 * enriched context also travels onto the worker: LineageQueryService copies it into the outbox
 * message, and LineageTaskConsumer rebuilds the worker's context from it.
 *
 * <h2>Forwarded headers</h2>
 * {@code X-Forwarded-For} is client-supplied and trivially spoofed, so it is only consulted when
 * {@code app.security.trust-forwarded-headers} is enabled - which is correct only when every
 * request genuinely arrives through a proxy that overwrites the header. Behind the nginx ingress
 * in helm/, {@code getRemoteAddr()} is the ingress pod's address and the real client is in the
 * header, so that deployment must enable it. Running exposed, it must not: an attacker would
 * otherwise choose what the compliance trail records about them.
 */
@Slf4j
public class ClientOriginEnrichmentFilter extends OncePerRequestFilter {

    private static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";
    private static final String HEADER_REAL_IP = "X-Real-IP";
    private static final String HEADER_USER_AGENT = "User-Agent";
    static final String UNKNOWN = "UNKNOWN_ORIGIN";

    private final boolean trustForwardedHeaders;

    public ClientOriginEnrichmentFilter(boolean trustForwardedHeaders) {
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof CustomJwtAuthenticationToken token) {
            NationalIdentityContext identity = token.getIdentityContext();

            NationalIdentityContext enriched = new NationalIdentityContext(
                    identity.userId(),
                    identity.nationalId(),
                    identity.roles(),
                    identity.scopes(),
                    resolveClientIp(request),
                    valueOrUnknown(request.getHeader(HEADER_USER_AGENT)));

            CustomJwtAuthenticationToken enrichedToken =
                    new CustomJwtAuthenticationToken(token.getJwt(), enriched, token.getAuthorities());
            enrichedToken.setDetails(token.getDetails());
            SecurityContextHolder.getContext().setAuthentication(enrichedToken);
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (trustForwardedHeaders) {
            String forwardedFor = request.getHeader(HEADER_FORWARDED_FOR);
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                // The left-most entry is the originating client; the rest are intermediate proxies.
                return forwardedFor.split(",")[0].trim();
            }
            String realIp = request.getHeader(HEADER_REAL_IP);
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return valueOrUnknown(request.getRemoteAddr());
    }

    /** An explicit unknown is auditable. An invented plausible value is not. */
    private static String valueOrUnknown(String value) {
        return value != null && !value.isBlank() ? value : UNKNOWN;
    }
}
