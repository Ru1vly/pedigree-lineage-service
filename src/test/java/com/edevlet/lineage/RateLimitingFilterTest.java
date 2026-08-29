package com.edevlet.lineage;

import com.edevlet.lineage.infrastructure.ratelimit.RateLimitingFilter;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The rate limiter's decision logic, against a mocked Redis.
 *
 * <p>What matters here is that the count is the one Redis returns. The previous implementation
 * counted in an in-JVM Resilience4j registry, so the deployment-wide limit was silently multiplied
 * by the replica count; no unit test could have caught that, because within one JVM it looked
 * correct. Pinning the behaviour to the value the shared counter returns is what makes the limit
 * mean the same thing at 2 replicas and at 12.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitingFilterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    // The 429 body carries an Instant timestamp, so the mapper needs the same JSR-310 module the
    // application's does.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private RateLimitingFilter filterWithLimit(int limit) {
        return new RateLimitingFilter(redisTemplate, objectMapper, limit, Duration.ofMinutes(1));
    }

    private MockHttpServletRequest submissionRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/lineage/queries");
        request.setRequestURI("/api/v1/lineage/queries");
        return request;
    }

    @AfterEach
    void clearIdentity() {
        UserSecurityContextHolder.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void givenRedisReturns(Object result) {
        given(redisTemplate.execute(any(RedisScript.class), any(List.class), any())).willReturn(result);
    }

    @Test
    @DisplayName("A count at the limit is still allowed through")
    void countAtLimit_isAllowed() throws Exception {
        givenRedisReturns(List.of(10L, 30_000L));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithLimit(10).doFilter(submissionRequest(), response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("The first count past the limit is rejected with 429 and an accurate Retry-After")
    void countPastLimit_isRejected() throws Exception {
        givenRedisReturns(List.of(11L, 30_000L));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithLimit(10).doFilter(submissionRequest(), response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(429);
        // Derived from the window's remaining TTL, not a hardcoded constant.
        assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("The limit is keyed per user, so one caller's usage cannot exhaust another's")
    void limitIsKeyedPerUser() throws Exception {
        UserSecurityContextHolder.setContext(new NationalIdentityContext(
                "citizen-42", "12345678950", Set.of("ROLE_USER"), Set.of("lineage:read"), "10.0.0.9", "curl/8"));
        givenRedisReturns(List.of(1L, 60_000L));

        filterWithLimit(10).doFilter(submissionRequest(), new MockHttpServletResponse(), mock(FilterChain.class));

        verify(redisTemplate).execute(
                any(RedisScript.class),
                org.mockito.ArgumentMatchers.eq(List.of(RateLimitingFilter.KEY_PREFIX + "user:citizen-42")),
                any());
    }

    @Test
    @DisplayName("Redis being unreachable fails open rather than rejecting every citizen")
    void redisUnavailable_failsOpen() throws Exception {
        given(redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
                .willThrow(new org.springframework.dao.QueryTimeoutException("redis down"));
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filterWithLimit(10).doFilter(submissionRequest(), response, chain);

        // Rate limiting protects the census backend; it is not an authorization control. A counter
        // outage must not become a service outage.
        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Requests other than a query submission are not counted at all")
    void nonSubmissionRequest_isNotCounted() throws Exception {
        MockHttpServletRequest statusRequest = new MockHttpServletRequest("GET", "/api/v1/lineage/queries/abc");
        statusRequest.setRequestURI("/api/v1/lineage/queries/abc");
        FilterChain chain = mock(FilterChain.class);

        filterWithLimit(10).doFilter(statusRequest, new MockHttpServletResponse(), chain);

        verify(chain).doFilter(any(), any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), any(List.class), any());
    }
}
