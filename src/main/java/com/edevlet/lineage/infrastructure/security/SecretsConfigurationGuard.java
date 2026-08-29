package com.edevlet.lineage.infrastructure.security;

import com.edevlet.lineage.infrastructure.security.encryption.TcknEncryptionProperties;
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
 *
 * <p>It used to have a hole exactly where it mattered. {@code checkSecret} returned early unless
 * the configured value was <em>equal</em> to the known insecure default, so a blank or absent value
 * sailed through - and the guard read {@code app.security.encryption.master-key} with a blank
 * default of its own, while {@code VaultTransitTcknEncryptionService} declared a <em>different</em>
 * hardcoded fallback secret in its constructor. Deleting one line from application.yml therefore
 * produced a production deployment that booted cleanly, encrypted every citizen's TCKN under a key
 * committed to this repository, and passed the guard whose sole purpose was to prevent that. Both
 * halves are closed: missing is now treated exactly like insecure, and the service has no fallback
 * secret at all.
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
    private final TcknEncryptionProperties encryptionProperties;

    @Value("${app.security.jwt.secret:}")
    private String jwtSecret;

    @Value("${spring.cloud.vault.token:}")
    private String vaultToken;

    @Value("${spring.cloud.vault.enabled:false}")
    private boolean vaultConfigEnabled;

    @Value("${app.security.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Value("${app.security.jwt.issuer-uri:}")
    private String issuerUri;

    public SecretsConfigurationGuard(Environment environment, TcknEncryptionProperties encryptionProperties) {
        this.environment = environment;
        this.encryptionProperties = encryptionProperties;
    }

    @PostConstruct
    void verifyNoInsecureDefaultsInProduction() {
        boolean isProduction = environment.matchesProfiles("production");

        checkExternalIdentityProviderConfigured(isProduction);
        checkSecret("app.security.jwt.secret", jwtSecret, INSECURE_DEFAULT_JWT_SECRET, isProduction);
        checkEncryptionKeyring(isProduction);
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

    /**
     * Checks the secret that actually encrypts, which since the keyring exists is
     * {@code keys.<active-key-id>} when set and {@code master-key} otherwise - not whichever
     * property happens to be readable. The KDF salt is checked too: it is not a secret, but leaving
     * it at the shipped default means every deployment of this service derives its keys from the
     * same salt, which is precisely the precomputation-sharing a salt exists to prevent.
     */
    private void checkEncryptionKeyring(boolean isProduction) {
        String activeKeyId = encryptionProperties.getActiveKeyId();
        String activeSecret = encryptionProperties.getKeys().getOrDefault(
                activeKeyId, encryptionProperties.getMasterKey());
        String propertyName = encryptionProperties.getKeys().containsKey(activeKeyId)
                ? "app.security.encryption.keys." + activeKeyId
                : "app.security.encryption.master-key";

        checkSecret(propertyName, activeSecret, INSECURE_DEFAULT_ENCRYPTION_KEY, isProduction);

        if (TcknEncryptionProperties.KeyDerivation.INSECURE_DEFAULT_SALT
                .equals(encryptionProperties.getKdf().getSalt())) {
            reportInsecureValue("app.security.encryption.kdf.salt",
                    "is still the shipped default, so this deployment derives its encryption keys from the "
                            + "same salt as every other. Set a stable per-environment value - and never change "
                            + "it once rows exist, because it re-derives every key",
                    isProduction);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Blank counts as a failure, not a pass. An unset secret is not "no insecure default in use" -
     * it is a property nobody chose, which either fails obscurely later or, as it did here, lets a
     * hardcoded fallback somewhere else become the real key.
     */
    private void checkSecret(String propertyName, String actualValue, String insecureDefault, boolean isProduction) {
        if (!hasText(actualValue)) {
            reportInsecureValue(propertyName,
                    "is not set. Provide a real, per-environment value (see values-prod.yaml)",
                    isProduction);
            return;
        }
        if (insecureDefault.equals(actualValue)) {
            reportInsecureValue(propertyName,
                    "is still set to its insecure development default. Set a real, per-environment value "
                            + "(see values-prod.yaml)",
                    isProduction);
        }
    }

    private void reportInsecureValue(String propertyName, String problem, boolean isProduction) {
        if (isProduction) {
            throw new IllegalStateException(
                    "%s %s before running with the 'production' profile.".formatted(propertyName, problem));
        }
        log.warn("{} {}. This is only acceptable for local development.", propertyName, problem);
    }
}
