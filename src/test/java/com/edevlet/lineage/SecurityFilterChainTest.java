package com.edevlet.lineage;

import com.edevlet.lineage.config.TestConfig;
import com.edevlet.lineage.dto.LineageQueryRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the REAL Spring Security filter chain end to end - unlike LineageQueryControllerTest
 * (which runs with {@code addFilters = false}) and LineageIntegrationTest (which calls the
 * service layer directly). This is what actually proves JWTs are validated, roles enforced, and
 * unauthorized access rejected at the HTTP layer, and it regression-tests that per-user rate
 * limiting is genuinely wired into the authenticated request path (see RateLimitingFilter /
 * SecurityConfig#addFilterAfter).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestConfig.class)
class SecurityFilterChainTest {

    private static final String TEST_JWT_SECRET = "test-only-hmac-secret-not-for-production-use-32-bytes-minimum";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("A request with no Authorization header is rejected before it reaches the controller")
    void missingToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/v1/lineage/queries/{id}", "some-tx-id"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A garbage bearer token is rejected, not silently accepted")
    void malformedToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/v1/lineage/queries/{id}", "some-tx-id")
                        .header("Authorization", "Bearer not-a-real-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A validly signed JWT authenticates and reaches the controller (404 for an unknown task, not 401)")
    void validToken_authenticatesAndReachesController() throws Exception {
        String token = signedJwt("user-sft-1", List.of("USER"));

        mockMvc.perform(get("/api/v1/lineage/queries/{id}", "does-not-exist")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("A USER-role token cannot reach the admin audit-log endpoint")
    void userRole_isDeniedOnAdminEndpoint() throws Exception {
        String token = signedJwt("user-sft-2", List.of("USER"));

        mockMvc.perform(get("/api/v1/lineage/admin/audit-logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An ADMIN-role token can reach the admin audit-log endpoint")
    void adminRole_isAllowedOnAdminEndpoint() throws Exception {
        String token = signedJwt("user-sft-3", List.of("ADMIN"));

        mockMvc.perform(get("/api/v1/lineage/admin/audit-logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Per-user rate limiting actually engages for authenticated requests (regression test for the filter-ordering bug)")
    void rateLimiting_engagesForAuthenticatedUser() throws Exception {
        String token = signedJwt("user-sft-rate-limit", List.of("USER"));

        int accepted = 0;
        int rateLimited = 0;
        for (int i = 0; i < 12; i++) {
            LineageQueryRequest request = LineageQueryRequest.builder()
                    .nationalId("12345678950")
                    .generationsDepth(1)
                    .idempotencyKey("rate-limit-test-" + UUID.randomUUID())
                    .build();

            int status = mockMvc.perform(post("/api/v1/lineage/queries")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andReturn().getResponse().getStatus();

            if (status == 429) {
                rateLimited++;
            } else if (status == 202) {
                accepted++;
            }
        }

        // application-test.yml / application.yml configure lineageIngress at 10/minute.
        org.junit.jupiter.api.Assertions.assertTrue(accepted <= 10,
                "expected at most 10 accepted requests before the limiter engaged, got " + accepted);
        org.junit.jupiter.api.Assertions.assertTrue(rateLimited > 0,
                "expected at least one 429 once the per-user limit was exceeded");
    }

    @Test
    @DisplayName("A citizen token cannot read /actuator/env, which would leak every configured secret")
    void userRole_isDeniedOnActuatorEnv() throws Exception {
        String token = signedJwt("user-sft-actuator", List.of("USER"));

        // /actuator/env and /actuator/loggers used to fall through to anyRequest().authenticated(),
        // so any citizen JWT could pull a full environment dump - the JWT signing secret, the TCKN
        // encryption master key, DB and Vault credentials - or POST new log levels at runtime.
        mockMvc.perform(get("/actuator/env")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/loggers")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unauthenticated liveness endpoints stay public")
    void healthEndpoints_remainPublic() throws Exception {
        // Asserts reachability, not health: locking down /actuator/** must not take the probe
        // endpoints with it. The status may legitimately be 503 when a dependency is down in the
        // test environment - what matters is that security did not answer 401/403.
        int status = mockMvc.perform(get("/actuator/health"))
                .andReturn().getResponse().getStatus();

        org.junit.jupiter.api.Assertions.assertTrue(status != 401 && status != 403,
                "/actuator/health must stay reachable without a token, got " + status);
    }

    @Test
    @DisplayName("A token with no national identity claim is rejected instead of being given a placeholder")
    void tokenWithoutNationalId_isRejected() throws Exception {
        // The converter used to substitute 10000000000 here - a value that does not even pass this
        // service's own TcknValidator - which silently attributed real lineage queries and their
        // audit rows to an identity nobody holds.
        String token = signedJwtWithoutNationalId("user-sft-no-tckn");

        mockMvc.perform(get("/api/v1/lineage/queries/{id}", "some-tx-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A token carrying a malformed national identity claim is rejected")
    void tokenWithMalformedNationalId_isRejected() throws Exception {
        String token = signedJwt("user-sft-bad-tckn", List.of("USER"), "10000000000");

        mockMvc.perform(get("/api/v1/lineage/queries/{id}", "some-tx-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    private String signedJwt(String subject, List<String> roles) throws Exception {
        return signedJwt(subject, roles, "12345678950");
    }

    private String signedJwt(String subject, List<String> roles, String nationalId) throws Exception {
        return sign(new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("national_id", nationalId)
                .claim("roles", roles));
    }

    private String signedJwtWithoutNationalId(String subject) throws Exception {
        return sign(new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("roles", List.of("USER")));
    }

    private String sign(JWTClaimsSet.Builder builder) throws Exception {
        JWTClaimsSet claims = builder
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJWT.sign(new MACSigner(TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)));
        return signedJWT.serialize();
    }
}
