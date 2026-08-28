package com.edevlet.lineage;

import com.edevlet.lineage.infrastructure.security.encryption.TcknEncryptionService;
import com.edevlet.lineage.infrastructure.security.encryption.VaultTransitTcknEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcknEncryptionServiceTest {

    private TcknEncryptionService encryptionService;

    @BeforeEach
    void setUp() {
        encryptionService = new VaultTransitTcknEncryptionService(
                null,
                false,
                "unit-test-field-level-encryption-master-key-2026"
        );
    }

    @Test
    @DisplayName("Should encrypt raw 11-digit TCKN into ciphertext payload for storage at rest")
    void shouldEncryptRawTckn() {
        String rawTckn = "12345678950";

        String cipherText = encryptionService.encrypt(rawTckn);

        assertThat(cipherText).isNotNull();
        assertThat(cipherText).isNotEqualTo(rawTckn);
        assertThat(encryptionService.isEncrypted(cipherText)).isTrue();
        assertThat(cipherText).startsWith("enc:v1:gcm:");
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
    @DisplayName("Should fail loudly when envelope ciphertext cannot be decrypted")
    void shouldThrowOnCorruptEnvelopeCiphertext() {
        assertThatThrownBy(() -> encryptionService.decrypt("enc:v1:gcm:not-valid-base64-%%%"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Plaintext and blank values still pass through untouched")
    void shouldPassThroughUnencryptedValues() {
        assertThat(encryptionService.decrypt("12345678950")).isEqualTo("12345678950");
        assertThat(encryptionService.decrypt("")).isEmpty();
        assertThat(encryptionService.decrypt(null)).isNull();
    }
}
