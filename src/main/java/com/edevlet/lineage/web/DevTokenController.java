package com.edevlet.lineage.web;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues short-lived demo JWTs signed with the app's own configured HMAC secret, so the bundled
 * static/index.html demo can actually complete a request end to end (previously it hardcoded a
 * literal "Bearer demo-token-12345", which the app's own JWT decoder always rejected).
 * <p>
 * This is deliberately NOT a real OIDC flow - it exists purely so a local `docker-compose up`
 * has something that works out of the box, and it accepts whatever identity the caller asks for
 * with no credential check at all. It is excluded from any "production" deployment (Helm always
 * sets SPRING_PROFILES_ACTIVE=production - see api-deployment.yaml / worker-deployment.yaml /
 * worker-rollout.yaml), and the path is additionally scoped narrowly in SecurityConfig's
 * permitAll list for defense in depth.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/lineage/dev")
@Profile("!production")
public class DevTokenController {

    private final String jwtSecret;

    public DevTokenController(@Value("${app.security.jwt.secret}") String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    @PostConstruct
    void warnOnStartup() {
        log.warn("DevTokenController is active: /api/v1/lineage/dev/token mints valid JWTs for any " +
                "identity with no credential check. This must never be reachable outside local " +
                "development - it is compiled into every build but only wired into the security " +
                "chain's permitAll list, and only present at all outside the 'production' profile.");
    }

    public record DevTokenRequest(String userId, String nationalId, List<String> roles) {
    }

    public record DevTokenResponse(String token) {
    }

    @PostMapping("/token")
    public ResponseEntity<DevTokenResponse> issueToken(@RequestBody(required = false) DevTokenRequest request) throws Exception {
        String userId = resolveUserId(request);
        String nationalId = resolveNationalId(request);
        List<String> roles = resolveRoles(request);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(userId)
                .claim("national_id", nationalId)
                .claim("roles", roles)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        signedJWT.sign(new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8)));

        return ResponseEntity.ok(new DevTokenResponse(signedJWT.serialize()));
    }

    private static String resolveUserId(DevTokenRequest request) {
        if (request != null && hasText(request.userId())) {
            return request.userId();
        }
        return "demo-user-" + UUID.randomUUID();
    }

    private static String resolveNationalId(DevTokenRequest request) {
        if (request != null && hasText(request.nationalId())) {
            return request.nationalId();
        }
        return "12345678950";
    }

    private static List<String> resolveRoles(DevTokenRequest request) {
        if (request != null && request.roles() != null && !request.roles().isEmpty()) {
            return request.roles();
        }
        return List.of("USER");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
