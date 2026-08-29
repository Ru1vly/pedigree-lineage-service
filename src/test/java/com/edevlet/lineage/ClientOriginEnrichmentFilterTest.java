package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.infrastructure.security.ClientOriginEnrichmentFilter;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The origin of a request is a compliance field, so both halves matter: that it is recorded at
 * all, and that a caller cannot choose what gets recorded about them.
 */
class ClientOriginEnrichmentFilterTest {

    @BeforeEach
    void authenticate() {
        UserSecurityContextHolder.setContext(new NationalIdentityContext(
                "citizen-1", "12345678950", Set.of("ROLE_USER"), Set.of("lineage:read"), null, null));
    }

    @AfterEach
    void clear() {
        UserSecurityContextHolder.clear();
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/lineage/queries/x");
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("User-Agent", "Mozilla/5.0 (e-Devlet)");
        return request;
    }

    private void run(ClientOriginEnrichmentFilter filter, MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));
    }

    @Test
    @DisplayName("The caller's IP and user agent land on the identity context")
    void originIsRecorded() throws Exception {
        run(new ClientOriginEnrichmentFilter(false), request());

        NationalIdentityContext enriched = UserSecurityContextHolder.getRequiredContext();
        // CustomJwtAuthenticationConverter cannot supply these - it never sees the request - so
        // without this filter every audit row's ip_address was empty.
        assertThat(enriched.ipAddress()).isEqualTo("10.1.2.3");
        assertThat(enriched.userAgent()).isEqualTo("Mozilla/5.0 (e-Devlet)");
        // Identity itself must be carried through untouched.
        assertThat(enriched.userId()).isEqualTo("citizen-1");
        assertThat(enriched.nationalId()).isEqualTo("12345678950");
        assertThat(enriched.roles()).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("X-Forwarded-For is ignored when forwarded headers are not trusted")
    void forwardedHeaderIsIgnoredByDefault() throws Exception {
        MockHttpServletRequest spoofed = request();
        spoofed.addHeader("X-Forwarded-For", "203.0.113.99");

        run(new ClientOriginEnrichmentFilter(false), spoofed);

        // Trusting a client-supplied header on a directly exposed deployment would let the caller
        // choose what the compliance trail records about them.
        assertThat(UserSecurityContextHolder.getRequiredContext().ipAddress()).isEqualTo("10.1.2.3");
    }

    @Test
    @DisplayName("Behind a trusted proxy the left-most forwarded entry is the client")
    void forwardedHeaderIsUsedWhenTrusted() throws Exception {
        MockHttpServletRequest proxied = request();
        proxied.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.1, 10.0.0.2");

        run(new ClientOriginEnrichmentFilter(true), proxied);

        // Behind the nginx ingress, getRemoteAddr() is the ingress pod; the citizen is in the header.
        assertThat(UserSecurityContextHolder.getRequiredContext().ipAddress()).isEqualTo("203.0.113.99");
    }

    @Test
    @DisplayName("A missing origin is recorded as an explicit unknown, not invented")
    void missingOriginIsExplicit() throws Exception {
        MockHttpServletRequest bare = new MockHttpServletRequest("GET", "/api/v1/lineage/queries/x");
        bare.setRemoteAddr(null);

        run(new ClientOriginEnrichmentFilter(true), bare);

        NationalIdentityContext enriched = UserSecurityContextHolder.getRequiredContext();
        assertThat(enriched.ipAddress()).isEqualTo("UNKNOWN_ORIGIN");
        assertThat(enriched.userAgent()).isEqualTo("UNKNOWN_ORIGIN");
    }

    @Test
    @DisplayName("An unauthenticated request passes through without inventing an identity")
    void unauthenticatedRequestIsUntouched() throws Exception {
        UserSecurityContextHolder.clear();

        run(new ClientOriginEnrichmentFilter(false), request());

        assertThat(UserSecurityContextHolder.getContext()).isEmpty();
    }
}
