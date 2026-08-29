package com.edevlet.lineage;

import com.edevlet.lineage.infrastructure.security.SecretsConfigurationGuard;
import com.edevlet.lineage.infrastructure.security.encryption.TcknEncryptionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The guard exists to turn "forgot to override a secret before deploying" into a startup failure.
 * It had a hole exactly where that mattered: it only fired when a value <em>equalled</em> a known
 * insecure default, so a blank or absent value passed - and the encryption service carried its own
 * hardcoded fallback secret, a different literal from the one the guard knew about. Deleting one
 * line from application.yml therefore produced a production deployment that booted cleanly and
 * encrypted every citizen's TCKN under a key committed to this repository.
 */
class SecretsConfigurationGuardTest {

    private static final String REAL_SECRET = "a-real-per-environment-secret-value-2026";

    private static SecretsConfigurationGuard guard(
            boolean production, String jwtSecret, TcknEncryptionProperties encryptionProperties, String issuerUri) {
        MockEnvironment environment = new MockEnvironment();
        if (production) {
            environment.setActiveProfiles("production");
        }
        SecretsConfigurationGuard guard = new SecretsConfigurationGuard(environment, encryptionProperties);
        ReflectionTestUtils.setField(guard, "jwtSecret", jwtSecret);
        ReflectionTestUtils.setField(guard, "issuerUri", issuerUri);
        ReflectionTestUtils.setField(guard, "jwkSetUri", "");
        ReflectionTestUtils.setField(guard, "vaultToken", "");
        ReflectionTestUtils.setField(guard, "vaultConfigEnabled", false);
        return guard;
    }

    private static TcknEncryptionProperties encryptionProperties(String masterKey) {
        TcknEncryptionProperties properties = new TcknEncryptionProperties();
        properties.setMasterKey(masterKey);
        properties.getKdf().setSalt("a-real-per-environment-kdf-salt");
        return properties;
    }

    private static void verify(SecretsConfigurationGuard guard) {
        ReflectionTestUtils.invokeMethod(guard, "verifyNoInsecureDefaultsInProduction");
    }

    @Test
    @DisplayName("A fully configured production deployment starts")
    void fullyConfiguredProduction_Starts() {
        assertThatCode(() -> verify(guard(true, REAL_SECRET, encryptionProperties(REAL_SECRET),
                "https://idp.example.gov.tr/realms/e-devlet")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A blank encryption key fails production startup instead of passing the check")
    void blankEncryptionKey_FailsInProduction() {
        assertThatThrownBy(() -> verify(guard(true, REAL_SECRET, encryptionProperties(""),
                "https://idp.example.gov.tr/realms/e-devlet")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.encryption.master-key")
                .hasMessageContaining("is not set");
    }

    @Test
    @DisplayName("A blank JWT secret fails production startup instead of passing the check")
    void blankJwtSecret_FailsInProduction() {
        assertThatThrownBy(() -> verify(guard(true, "", encryptionProperties(REAL_SECRET),
                "https://idp.example.gov.tr/realms/e-devlet")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.jwt.secret");
    }

    @Test
    @DisplayName("The shipped insecure defaults still fail production startup")
    void insecureDefaults_FailInProduction() {
        assertThatThrownBy(() -> verify(guard(true, REAL_SECRET,
                encryptionProperties(SecretsConfigurationGuard.INSECURE_DEFAULT_ENCRYPTION_KEY),
                "https://idp.example.gov.tr/realms/e-devlet")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insecure development default");
    }

    @Test
    @DisplayName("The key that is actually in use is the one checked, not whichever property is readable")
    void keyringEntry_IsCheckedRatherThanTheLegacyProperty() {
        // master-key set to something real, but the active key comes from the keyring and is the
        // shipped default. Checking master-key alone would wave this through.
        TcknEncryptionProperties properties = encryptionProperties(REAL_SECRET);
        properties.setActiveKeyId("2026-q2");
        properties.setKeys(Map.of("2026-q2", SecretsConfigurationGuard.INSECURE_DEFAULT_ENCRYPTION_KEY));

        assertThatThrownBy(() -> verify(guard(true, REAL_SECRET, properties,
                "https://idp.example.gov.tr/realms/e-devlet")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.encryption.keys.2026-q2");
    }

    @Test
    @DisplayName("The shipped KDF salt fails production startup, so every deployment does not share one salt")
    void defaultKdfSalt_FailsInProduction() {
        TcknEncryptionProperties properties = new TcknEncryptionProperties();
        properties.setMasterKey(REAL_SECRET);

        assertThat(properties.getKdf().getSalt())
                .isEqualTo(TcknEncryptionProperties.KeyDerivation.INSECURE_DEFAULT_SALT);
        assertThatThrownBy(() -> verify(guard(true, REAL_SECRET, properties,
                "https://idp.example.gov.tr/realms/e-devlet")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.security.encryption.kdf.salt");
    }

    @Test
    @DisplayName("Production without an external identity provider is refused")
    void noIdentityProvider_FailsInProduction() {
        assertThatThrownBy(() -> verify(guard(true, REAL_SECRET, encryptionProperties(REAL_SECRET), "")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No external identity provider is configured");
    }

    @Test
    @DisplayName("Outside production the same problems warn rather than block a local run")
    void nonProduction_WarnsRatherThanFails() {
        assertThatCode(() -> verify(guard(false, "", new TcknEncryptionProperties(), "")))
                .doesNotThrowAnyException();
    }
}
