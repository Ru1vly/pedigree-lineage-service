package com.edevlet.lineage.infrastructure.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Every secret-bearing property in this application (JWT signing secret, TCKN encryption
 * fallback key, Vault token) ships with an insecure default in application.yml, so a fresh
 * clone still boots for local development without extra setup. This guard is what turns
 * "forgot to override it before deploying" into a startup failure under the "production"
 * Spring profile instead of a silent leak.
 */
@Slf4j
@Component
public class SecretsConfigurationGuard {

    public static final String INSECURE_DEFAULT_JWT_SECRET =
            "local-dev-only-insecure-default-CHANGE-ME-2026-not-for-production-use";
    public static final String INSECURE_DEFAULT_ENCRYPTION_KEY =
            "local-dev-only-insecure-default-CHANGE-ME-2026-not-for-production-use-tckn-key";
    public static final String INSECURE_DEFAULT_VAULT_TOKEN = "root";

    private final Environment environment;

    @Value("${app.security.jwt.secret:}")
    private String jwtSecret;

    @Value("${app.security.encryption.master-key:}")
    private String encryptionMasterKey;

    @Value("${spring.cloud.vault.token:}")
    private String vaultToken;

    @Value("${spring.cloud.vault.enabled:false}")
    private boolean vaultConfigEnabled;

    @Value("${app.security.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Value("${app.security.jwt.issuer-uri:}")
    private String issuerUri;

    public SecretsConfigurationGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void verifyNoInsecureDefaultsInProduction() {
        boolean isProduction = environment.matchesProfiles("production");

        checkExternalIdentityProviderConfigured(isProduction);
        checkSecret("app.security.jwt.secret", jwtSecret, INSECURE_DEFAULT_JWT_SECRET, isProduction);
        checkSecret("app.security.encryption.master-key", encryptionMasterKey, INSECURE_DEFAULT_ENCRYPTION_KEY, isProduction);
        if (vaultConfigEnabled) {
            checkSecret("spring.cloud.vault.token", vaultToken, INSECURE_DEFAULT_VAULT_TOKEN, isProduction);
        }
    }

    /**
     * A symmetric HS256 secret is a development affordance, not an identity provider: the string
     * that verifies a token is the same one that signs it, so anyone who can read it - an operator,
     * a leaked config dump, DevTokenController - can mint a token for any user and any role. Under
     * the "production" profile an external OIDC provider must be configured so validation is
     * asymmetric and this service holds public keys only.
     */
    private void checkExternalIdentityProviderConfigured(boolean isProduction) {
        boolean idpConfigured = hasText(jwkSetUri) || hasText(issuerUri);
        if (idpConfigured) {
            return;
        }
        if (isProduction) {
            throw new IllegalStateException(
                    "No external identity provider is configured: set app.security.jwt.jwk-set-uri or " +
                            "app.security.jwt.issuer-uri before running with the 'production' profile. " +
                            "Validating tokens with the shared HS256 secret app.security.jwt.secret lets " +
                            "any holder of that one string forge tokens for any user or role.");
        }
        log.warn("No external identity provider configured; falling back to shared-secret HS256 token " +
                "validation. This is only acceptable for local development.");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void checkSecret(String propertyName, String actualValue, String insecureDefault, boolean isProduction) {
        if (!insecureDefault.equals(actualValue)) {
            return;
        }
        if (isProduction) {
            throw new IllegalStateException(
                    ("%s is still set to its insecure development default. Set a real, " +
                            "per-environment value (see values-prod.yaml) before running with the " +
                            "'production' profile.").formatted(propertyName));
        }
        log.warn("{} is using its insecure development default. This is only acceptable for local development.", propertyName);
    }
}
