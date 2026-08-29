package com.edevlet.lineage;

import com.edevlet.lineage.infrastructure.security.encryption.TcknEncryptionProperties;
import com.edevlet.lineage.infrastructure.security.encryption.TcknEncryptionService;
import com.edevlet.lineage.infrastructure.security.encryption.VaultTransitTcknEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcknEncryptionServiceTest {

    private static final String MASTER_KEY = "unit-test-field-level-encryption-master-key-2026";

    private TcknEncryptionService encryptionService;

    /**
     * PBKDF2 runs once per key at construction, so the iteration count is boot cost rather than
     * per-row cost. The floor in {@code TcknEncryptionProperties} applies regardless of what is set
     * here; these tests derive two or three keys and pay it a handful of times.
     */
    private static TcknEncryptionProperties properties(String masterKey) {
        TcknEncryptionProperties properties = new TcknEncryptionProperties();
        properties.setMasterKey(masterKey);
        return properties;
    }

    private static TcknEncryptionService serviceFor(TcknEncryptionProperties properties) {
        return new VaultTransitTcknEncryptionService(null, false, properties);
    }

    @BeforeEach
    void setUp() {
        encryptionService = serviceFor(properties(MASTER_KEY));
    }

    @Test
    @DisplayName("Should encrypt raw 11-digit TCKN into ciphertext payload for storage at rest")
    void shouldEncryptRawTckn() {
        String rawTckn = "12345678950";

        String cipherText = encryptionService.encrypt(rawTckn);

        assertThat(cipherText).isNotNull();
        assertThat(cipherText).isNotEqualTo(rawTckn);
        assertThat(encryptionService.isEncrypted(cipherText)).isTrue();
        // The key id is part of the payload. Without it a ciphertext says nothing about which key
        // wrote it, which is what made the old format impossible to rotate.
        assertThat(cipherText).startsWith("enc:v2:primary:");
    }

    @Test
    @DisplayName("Should decrypt ciphertext back to original unmasked 11-digit TCKN")
    void shouldDecryptCiphertextToOriginalTckn() {
        String rawTckn = "98765432100";

        String cipherText = encryptionService.encrypt(rawTckn);
        String decryptedTckn = encryptionService.decrypt(cipherText);

        assertThat(decryptedTckn).isEqualTo(rawTckn);
    }

    @Test
    @DisplayName("Should mask TCKN correctly for secure logging and display")
    void shouldMaskTcknForDisplay() {
        String rawTckn = "12345678950";
        String cipherText = encryptionService.encrypt(rawTckn);

        String maskedFromRaw = encryptionService.mask(rawTckn);
        String maskedFromCipher = encryptionService.mask(cipherText);

        assertThat(maskedFromRaw).isEqualTo("123*****950");
        assertThat(maskedFromCipher).isEqualTo("123*****950");
    }

    @Test
    @DisplayName("Should return encrypted string as-is if encrypt is called twice (idempotent)")
    void shouldBeIdempotentOnEncrypt() {
        String rawTckn = "55566677788";

        String cipherText1 = encryptionService.encrypt(rawTckn);
        String cipherText2 = encryptionService.encrypt(cipherText1);

        assertThat(cipherText2).isEqualTo(cipherText1);
    }

    @Test
    @DisplayName("Rotating the active key still decrypts rows written under the previous key")
    void rotation_KeepsPreviouslyWrittenRowsReadable() {
        // Before: rotating TCKN_ENCRYPTION_MASTER_KEY orphaned every historical row permanently -
        // the ciphertext named no key, so nothing could tell which secret had produced it, and
        // decrypt() simply threw from then on.
        TcknEncryptionProperties beforeRotation = new TcknEncryptionProperties();
        beforeRotation.setActiveKeyId("2026-q1");
        beforeRotation.setKeys(Map.of("2026-q1", "first-generation-key-material"));

        String writtenUnderOldKey = serviceFor(beforeRotation).encrypt("12345678950");
        assertThat(writtenUnderOldKey).startsWith("enc:v2:2026-q1:");

        TcknEncryptionProperties afterRotation = new TcknEncryptionProperties();
        afterRotation.setActiveKeyId("2026-q2");
        afterRotation.setKeys(Map.of(
                "2026-q1", "first-generation-key-material",
                "2026-q2", "second-generation-key-material"));
        TcknEncryptionService rotated = serviceFor(afterRotation);

        assertThat(rotated.decrypt(writtenUnderOldKey)).isEqualTo("12345678950");
        assertThat(rotated.encrypt("12345678950")).startsWith("enc:v2:2026-q2:");
    }

    @Test
    @DisplayName("A ciphertext naming a key that was dropped from the keyring fails loudly and says which key")
    void missingKeyId_FailsWithAnActionableMessage() {
        TcknEncryptionProperties original = new TcknEncryptionProperties();
        original.setActiveKeyId("retired");
        original.setKeys(Map.of("retired", "retired-key-material"));
        String writtenUnderRetiredKey = serviceFor(original).encrypt("12345678950");

        assertThatThrownBy(() -> encryptionService.decrypt(writtenUnderRetiredKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retired");
    }

    @Test
    @DisplayName("Two keys derived from the same passphrase under different ids are genuinely different keys")
    void perKeyIdSalting_ProducesDistinctKeys() {
        TcknEncryptionProperties sameSecretTwoIds = new TcknEncryptionProperties();
        sameSecretTwoIds.setActiveKeyId("alpha");
        sameSecretTwoIds.setKeys(Map.of("alpha", MASTER_KEY, "beta", MASTER_KEY));
        TcknEncryptionService service = serviceFor(sameSecretTwoIds);

        String underAlpha = service.encrypt("12345678950");
        String relabelledAsBeta = underAlpha.replace("enc:v2:alpha:", "enc:v2:beta:");

        // Relabelling has to fail: the key id is salted into the derivation and authenticated as
        // GCM additional data, so a blob cannot be moved between key ids.
        assertThatThrownBy(() -> service.decrypt(relabelledAsBeta))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Legacy enc:v1:gcm rows written under the old SHA-256 derivation are still readable")
    void legacyV1Ciphertext_RemainsReadable() {
        // enc:v1:gcm: payloads were AES-GCM under SHA-256(master-key), with no key id and no AAD.
        // The keyring keeps that derivation available for reads only, so migrating does not mean
        // abandoning existing rows.
        String legacyCipherText = LegacyV1Fixture.encrypt(MASTER_KEY, "12345678950");

        assertThat(legacyCipherText).startsWith("enc:v1:gcm:");
        assertThat(encryptionService.decrypt(legacyCipherText)).isEqualTo("12345678950");
        // ...and the next write moves it to the current format.
        assertThat(encryptionService.encrypt("12345678950")).startsWith("enc:v2:");
    }

    @Test
    @DisplayName("Should fail loudly rather than hand back Vault ciphertext as if it were the plaintext TCKN")
    void shouldThrowRatherThanReturnUndecryptedVaultCiphertext() {
        // Shape of a real Vault Transit ciphertext, reaching decrypt() while Vault is unreachable
        // (vaultEnabled=false here, exactly as during an outage where the operations bean is absent).
        // The payload is not plain base64 of anything, so the wrapped-payload fallback cannot decode
        // it either. This used to fall through and return the argument unchanged: callers received
        // an encrypted blob believing it was the citizen's national ID, then stored, logged and
        // compared it as one.
        String vaultCipherText = "vault:v1:dGhpcyBpcyBub3Q6dmFsaWQ=:extra-segment";

        assertThatThrownBy(() -> encryptionService.decrypt(vaultCipherText))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt");
    }

    @Test
    @DisplayName("Should fail loudly when local ciphertext cannot be decrypted")
    void shouldThrowOnCorruptCiphertext() {
        assertThatThrownBy(() -> encryptionService.decrypt("enc:v2:primary:not-valid-base64-%%%"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> encryptionService.decrypt("enc:v1:gcm:not-valid-base64-%%%"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("A pre-encryption plaintext TCKN reads through, but arbitrary un-prefixed data does not")
    void unprefixedValues_AreDiscriminated() {
        // V2 of the schema is named encrypt_tckn_column but only widened the column - it backfilled
        // nothing - so genuinely unencrypted rows exist and must stay readable. What must NOT stay
        // readable is everything else: decrypt() used to return any unrecognised value unchanged,
        // which made corruption, un-handled ciphertext and legacy plaintext indistinguishable.
        assertThat(encryptionService.decrypt("12345678950")).isEqualTo("12345678950");
        assertThat(encryptionService.decrypt("")).isEmpty();
        assertThat(encryptionService.decrypt(null)).isNull();

        assertThatThrownBy(() -> encryptionService.decrypt("not-a-tckn-and-not-ciphertext"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unrecognised format");
        assertThatThrownBy(() -> encryptionService.decrypt("1234567895"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Starting with no key configured fails rather than falling back to a hardcoded secret")
    void missingKey_FailsStartup() {
        // The constructor used to default the master key to a literal in its own source file - a
        // different literal from the one SecretsConfigurationGuard checked for, so removing the
        // property from application.yml produced a clean boot on a committed key.
        assertThatThrownBy(() -> serviceFor(new TcknEncryptionProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No TCKN encryption key is configured");
    }

    /**
     * Produces a ciphertext in the retired {@code enc:v1:gcm:} format, so the read path for
     * already-stored rows is tested against something actually written that way rather than
     * against the current encoder.
     */
    private static final class LegacyV1Fixture {
        static String encrypt(String masterKey, String plainText) {
            try {
                byte[] keyBytes = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(masterKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] iv = new byte[12];
                new java.security.SecureRandom().nextBytes(iv);

                javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,
                        new javax.crypto.spec.SecretKeySpec(keyBytes, "AES"),
                        new javax.crypto.spec.GCMParameterSpec(128, iv));

                byte[] cipherBytes = cipher.doFinal(plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(iv.length + cipherBytes.length);
                buffer.put(iv).put(cipherBytes);
                return "enc:v1:gcm:" + java.util.Base64.getEncoder().encodeToString(buffer.array());
            } catch (Exception e) {
                throw new IllegalStateException("fixture failed to build a legacy v1 ciphertext", e);
            }
        }
    }
}
