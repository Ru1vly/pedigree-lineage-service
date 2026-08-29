package com.edevlet.lineage.infrastructure.security;

import com.edevlet.lineage.infrastructure.ratelimit.RateLimitingFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomJwtAuthenticationConverter customJwtAuthenticationConverter;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Ingress limit per user per window. Counted in Redis, so this is the limit for the whole
     * deployment rather than per pod - see RateLimitingFilter.
     */
    @Value("${app.ratelimit.lineage-ingress.limit-for-period:10}")
    private int rateLimitForPeriod;

    @Value("${app.ratelimit.lineage-ingress.refresh-period:1m}")
    private Duration rateLimitRefreshPeriod;

    /**
     * Whether X-Forwarded-For / X-Real-IP may be believed when recording a caller's origin.
     * Defaults to false because those headers are client-supplied: trusting them on a directly
     * exposed deployment lets a caller choose what the compliance trail records. Enable it only
     * behind a proxy that overwrites them - which the nginx ingress in helm/ does.
     */
    @Value("${app.security.trust-forwarded-headers:false}")
    private boolean trustForwardedHeaders;

    @Value("${app.security.jwt.secret}")
    private String jwtSecret;

    /** JWKS endpoint of the external OIDC provider; when set, tokens are validated asymmetrically. */
    @Value("${app.security.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    /** OIDC issuer; enables discovery when jwk-set-uri is absent, and is validated as the iss claim. */
    @Value("${app.security.jwt.issuer-uri:}")
    private String issuerUri;

    /** Expected aud claim. Left blank, audience is not checked. */
    @Value("${app.security.jwt.audience:}")
    private String audience;

    public SecurityConfig(CustomJwtAuthenticationConverter customJwtAuthenticationConverter,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper) {
        this.customJwtAuthenticationConverter = customJwtAuthenticationConverter;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Bean
    public RateLimitingFilter rateLimitingFilter() {
        return new RateLimitingFilter(redisTemplate, objectMapper, rateLimitForPeriod, rateLimitRefreshPeriod);
    }

    /**
     * Not a {@code @Component}, and registered below rather than as a servlet filter, for the same
     * reason as RateLimitingFilter: it must run once, inside the security chain, after
     * authentication.
     */
    @Bean
    public ClientOriginEnrichmentFilter clientOriginEnrichmentFilter() {
        return new ClientOriginEnrichmentFilter(trustForwardedHeaders);
    }

    @Bean
    public FilterRegistrationBean<ClientOriginEnrichmentFilter> clientOriginEnrichmentFilterRegistration(
            ClientOriginEnrichmentFilter filter) {
        FilterRegistrationBean<ClientOriginEnrichmentFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * RateLimitingFilter is added explicitly to the chain below, after JWT authentication, so it
     * can key limits on the authenticated user. Boot's blanket auto-registration of Filter beans
     * as generic servlet filters is disabled here so it doesn't ALSO run a second time, earlier
     * and unauthenticated, ahead of the security chain.
     */
    @Bean
    public FilterRegistrationBean<RateLimitingFilter> rateLimitingFilterRegistration(RateLimitingFilter filter) {
        FilterRegistrationBean<RateLimitingFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, RateLimitingFilter rateLimitingFilter) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/static/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        // Everything else under /actuator is operator surface, not citizen surface.
                        // This matcher MUST stay directly below the permitAll above and ahead of
                        // anyRequest(): without it /actuator/env and /actuator/loggers fall through
                        // to the generic authenticated() rule, which lets any low-privilege citizen
                        // token read a full environment dump (JWT secret, TCKN master key, DB and
                        // Vault credentials) and POST runtime log-level changes.
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // Dev-only JWT issuance for the bundled demo UI; the controller backing this
                        // path only exists outside the "production" profile (see DevTokenController).
                        .requestMatchers("/api/v1/lineage/dev/**").permitAll()
                        .requestMatchers("/api/v1/lineage/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/lineage/**").authenticated()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .jwtAuthenticationConverter(customJwtAuthenticationConverter)
                        )
                )
                // Order matters: the origin enrichment runs directly after authentication so the
                // identity context carries the caller's IP before anything downstream reads it -
                // including the rate limiter's client key and every audit row.
                .addFilterAfter(clientOriginEnrichmentFilter(), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(rateLimitingFilter, ClientOriginEnrichmentFilter.class);

        return http.build();
    }

    /**
     * Resolves the token validation strategy, preferring a real identity provider.
     *
     * <p>When {@code app.security.jwt.jwk-set-uri} (or {@code issuer-uri}) is configured, tokens are
     * validated asymmetrically against the IdP's published signing keys: this service holds only
     * public keys and cannot mint a token for anyone. Issuer and audience are checked as well, so a
     * valid token issued for a different relying party is rejected here rather than accepted as a
     * local identity.
     *
     * <p>Otherwise it falls back to the shared HS256 secret, which is a development-only mode: the
     * same string both signs and verifies, DevTokenController mints with it, and anyone holding it
     * can forge a token for any user or role - including ROLE_ADMIN. SecretsConfigurationGuard
     * refuses to start under the "production" profile unless an IdP is configured, so that mode
     * cannot reach production silently.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        if (StringUtils.hasText(jwkSetUri)) {
            return buildJwkSetDecoder();
        }
        if (StringUtils.hasText(issuerUri)) {
            return buildIssuerDecoder();
        }
        return buildSymmetricDecoder();
    }

    private JwtDecoder buildJwkSetDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .jwsAlgorithm(SignatureAlgorithm.ES256)
                .build();
        decoder.setJwtValidator(idpTokenValidator());
        return decoder;
    }

    private JwtDecoder buildIssuerDecoder() {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuerUri);
        decoder.setJwtValidator(idpTokenValidator());
        return decoder;
    }

    private JwtDecoder buildSymmetricDecoder() {
        SecretKeySpec secretKey = new SecretKeySpec(jwtSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(secretKey).build();
    }

    private OAuth2TokenValidator<Jwt> idpTokenValidator() {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(StringUtils.hasText(issuerUri)
                ? JwtValidators.createDefaultWithIssuer(issuerUri)
                : JwtValidators.createDefault());
        if (StringUtils.hasText(audience)) {
            validators.add(new JwtClaimValidator<List<String>>(
                    JwtClaimNames.AUD,
                    audienceList -> audienceList != null && audienceList.contains(audience)));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }
}
