package com.edevlet.lineage.config;

import com.edevlet.lineage.infrastructure.ratelimit.RateLimitingFilter;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Redis stand-ins for the pure-unit "test" profile, which starts no Redis container.
 *
 * <p>The script stub is not a blanket mock. RateLimitingFilter now counts permits with a Lua
 * INCR/PEXPIRE script, and a mock returning null would make the filter fail open on every call -
 * quietly turning SecurityFilterChainTest's rate-limiting regression test into one that asserts
 * nothing. The counter below reproduces the script's contract (post-increment count, remaining
 * TTL) in memory so the filter's own decision logic is still exercised. Keys outside the rate
 * limiter's namespace - the orchestrator's compare-and-delete unlock script - keep the previous
 * pass-through behaviour.
 *
 * <p>Redis itself is exercised for real in the Testcontainers profile, not here.
 *
 * <p>The bean methods are deliberately NOT named {@code stringRedisTemplate} /
 * {@code redisConnectionFactory}. RedisConfig declares beans by exactly those names, and
 * {@code spring.main.allow-bean-definition-overriding} is true under this profile, so a
 * same-named definition here was silently replaced by the real template - which then handed every
 * Redis-backed collaborator a template wired to a mocked connection factory. Distinct names let
 * {@code @Primary} resolve the injection point instead of one definition quietly overwriting the
 * other.
 */
@TestConfiguration
@Profile("test")
public class TestConfig {

    private final Map<String, AtomicLong> rateLimitCounters = new ConcurrentHashMap<>();

    @Bean
    @Primary
    public RedisConnectionFactory mockRedisConnectionFactory() {
        return Mockito.mock(RedisConnectionFactory.class);
    }

    @Bean
    @Primary
    @SuppressWarnings({"unchecked", "rawtypes"})
    public StringRedisTemplate mockStringRedisTemplate() {
        StringRedisTemplate mockTemplate = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(mockTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(Boolean.TRUE);

        when(mockTemplate.execute(any(RedisScript.class), any(List.class), any()))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(1);
                    if (keys.isEmpty() || !keys.get(0).startsWith(RateLimitingFilter.KEY_PREFIX)) {
                        // The distributed-lock unlock script: 1 means "released", which is what the
                        // orchestrator expects for a lock it still holds.
                        return 1L;
                    }
                    long count = rateLimitCounters
                            .computeIfAbsent(keys.get(0), key -> new AtomicLong())
                            .incrementAndGet();
                    return List.of(count, 60_000L);
                });

        return mockTemplate;
    }
}
