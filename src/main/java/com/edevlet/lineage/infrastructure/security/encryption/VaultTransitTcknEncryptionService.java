package com.edevlet.lineage.infrastructure.security.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultOperations;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Field-level encryption at rest for citizen national identity numbers, via HashiCorp Vault's
 * Transit engine where it is reachable and local AES-256-GCM where it is not, so that a PostgreSQL
 * dump or a DBA session yields ciphertext rather than TCKNs.
 *
 * <h2>The local mode is not envelope encryption, and no longer claims to be</h2>
 *
 * <p>Every comment, log line and document here used to call the local path "envelope encryption".
 * It is not. Envelope encryption generates a fresh data key per item, encrypts the item under it,
 * encrypts that data key under a master key, and stores the wrapped data key beside the ciphertext -
 * which is what gives you a key hierarchy and lets a master key rotate without touching the data.
 * This path has no data key and no wrapping: it is AES-256-GCM directly under one key derived from
 * configuration. That is a defensible design for an 11-character field - a per-row wrapped data key
 * would be larger than the plaintext, and Vault Transit already provides the hierarchy when it is
 * enabled - but it has to be called what it is, because the mitigations the two designs give you
 * are different and a reader who believes the wrong one will reason wrongly about rotation.
 *
 * <h2>Ciphertext formats</h2>
 *
 * <ul>
 *   <li>{@code vault:v1:...} - Vault Transit. Vault owns the key and its rotation.
 *   <li>{@code enc:v2:<keyId>:<base64(iv||ciphertext||tag)>} - local AES-256-GCM. The key id is
 *       part of the payload, so a row says which key encrypted it, and it is bound in as GCM
 *       additional authenticated data, so relabelling a blob to another key id fails the tag check
 *       instead of being attempted under the wrong key.
 *   <li>{@code enc:v1:gcm:<base64(iv||ciphertext||tag)>} - legacy, <b>read-only</b>. Its key was
 *       {@code SHA-256(master-key)} and it named no key id. Rows in this format are rewritten into
 *       {@code enc:v2} whenever their entity is next saved.
 * </ul>
 *
 * <h2>Nothing decrypts by guessing</h2>
 *
 * <p>{@code decrypt} used to return anything it did not recognise unchanged, so a caller could not
 * tell an unencrypted legacy row from a corrupted one from a ciphertext it had failed to handle -
 * and a Vault blob could be stored, logged and compared as though it were a citizen's TCKN. Now the
 * only value that passes through is one that is a well-formed 11-digit TCKN, which is the single
 * shape a pre-encryption row can legitimately have (V2 of the schema widened the column but never
 * backfilled it, so such rows genuinely exist). That read is logged at WARN. Anything else throws.
 */
@Slf4j
@Service
public class VaultTransitTcknEncryptionService implements TcknEncryptionService {

    private static final String VAULT_PREFIX = "vault:v1:";
    private static final String LOCAL_V2_PREFIX = "enc:v2:";
    private static final String LEGACY_V1_PREFIX = "enc:v1:gcm:";
    private static final String TRANSIT_KEY_NAME = "tckn-key";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int TCKN_LENGTH = 11;

    /** The one shape an un-prefixed stored value is allowed to have: a pre-encryption TCKN. */
    private static final Pattern LEGACY_PLAINTEXT_TCKN = Pattern.compile("\\d{" + TCKN_LENGTH + "}");

    private final VaultOperations vaultOperations;
    private final boolean vaultEnabled;
    private final TcknEncryptionKeyring keyring;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public VaultTransitTcknEncryptionService(
            @Autowired(required = false) VaultOperations vaultOperations,
            @Value("${app.security.vault.enabled:false}") boolean vaultEnabled,
            TcknEncryptionProperties encryptionProperties) {
        this.vaultOperations = vaultOperations;
        this.vaultEnabled = vaultEnabled;
        // No hardcoded fallback secret. This constructor used to default the master key to a
        // literal committed in this file - a different literal from the one SecretsConfigurationGuard
        // checks for, so deleting one line of application.yml booted production cleanly on a key
        // that is in the source tree and past the guard whose whole purpose was to stop that.
        // A missing key is now a startup failure raised by the keyring.
        this.keyring = TcknEncryptionKeyring.from(encryptionProperties);
        log.info("Initialized Field-Level Data Encryption Service (Vault Transit Enabled: {})", vaultEnabled);
    }

    @Override
    public String encrypt(String plainTckn) {
        if (plainTckn == null || plainTckn.isBlank() || isEncrypted(plainTckn)) {
            return plainTckn;
        }

        if (vaultEnabled && vaultOperations != null) {
            String vaultCipher = encryptViaVault(plainTckn);
            if (vaultCipher != null) {
                return vaultCipher;
            }
        }

        return encryptLocal(plainTckn);
    }

    private String encryptViaVault(String plainTckn) {
        try {
            log.debug("Encrypting TCKN via HashiCorp Vault Transit Engine");
            String cipherText = vaultOperations.opsForTransit().encrypt(TRANSIT_KEY_NAME, plainTckn);
            if (cipherText != null && cipherText.startsWith(VAULT_PREFIX)) {
                return cipherText;
            } else if (cipherText != null) {
                return VAULT_PREFIX + Base64.getEncoder().encodeToString(cipherText.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception vaultException) {
            log.warn("Vault Transit Engine encryption call failed, using local AES-256-GCM fallback: {}",
                    vaultException.getMessage());
        }
        return null;
    }

    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            return cipherText;
        }

        if (cipherText.startsWith(VAULT_PREFIX)) {
            return decryptVaultCiphertext(cipherText);
        }

        if (cipherText.startsWith(LOCAL_V2_PREFIX)) {
            return decryptLocalV2(cipherText);
        }

        if (cipherText.startsWith(LEGACY_V1_PREFIX)) {
            return decryptLegacyV1(cipherText);
        }

        return readUnprefixedValue(cipherText);
    }

    /**
     * The only tolerated un-prefixed value, and the reason it is tolerated: {@code V2} of the
     * schema is named {@code encrypt_tckn_column} but only widened the column to VARCHAR(512) - it
     * backfilled nothing - so rows written before field-level encryption existed are still sitting
     * there in the clear. Refusing them outright would make those citizens' tasks permanently
     * unreadable. Accepting <em>anything</em> un-prefixed, which is what this did, meant corruption
     * and un-handled ciphertext were indistinguishable from them. An 11-digit string is the shape
     * such a row must have; everything else is a bug and is treated as one.
     */
    private String readUnprefixedValue(String storedValue) {
        if (LEGACY_PLAINTEXT_TCKN.matcher(storedValue).matches()) {
            log.warn("Read a national ID that is stored unencrypted (pre-encryption row). It will be "
                    + "encrypted the next time its entity is saved.");
            return storedValue;
        }
        // No masking or echoing of the value: it is not a TCKN, but it is not known to be safe either.
        log.error("Stored national ID is neither a recognised ciphertext format nor a well-formed "
                + "11-digit TCKN (length={}). Refusing to guess.", storedValue.length());
        throw new IllegalStateException(
                "Stored national ID is in an unrecognised format; it is neither ciphertext this service "
                        + "can decrypt nor a legacy plaintext TCKN.");
    }

    private String decryptVaultCiphertext(String cipherText) {
        Exception vaultFailure = null;
        if (vaultEnabled && vaultOperations != null) {
            try {
                log.debug("Decrypting TCKN via HashiCorp Vault Transit Engine");
                return vaultOperations.opsForTransit().decrypt(TRANSIT_KEY_NAME, cipherText);
            } catch (Exception exception) {
                vaultFailure = exception;
                log.warn("Vault Transit Engine decryption failed for vault prefix, trying payload fallback: {}", exception.getMessage());
            }
        }

        // Fallback for the wrapped base64 vault payload written by encrypt() when Transit
        // returned a value without its own prefix. If decode fails, fail loudly to avoid
        // leaking ciphertext as plaintext.
        String payload = cipherText.substring(VAULT_PREFIX.length());
        try {
            return new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8);
        } catch (Exception decodeException) {
            Exception rootCause = vaultFailure != null ? vaultFailure : decodeException;
            log.error("Unable to decrypt Vault-prefixed TCKN ciphertext: {}", rootCause.getMessage(), rootCause);
            throw new IllegalStateException("Failed to decrypt Vault-prefixed TCKN ciphertext", rootCause);
        }
    }

    @Override
    public boolean isEncrypted(String text) {
        if (text == null) {
            return false;
        }
        return text.startsWith(VAULT_PREFIX)
                || text.startsWith(LOCAL_V2_PREFIX)
                || text.startsWith(LEGACY_V1_PREFIX);
    }

    @Override
    public String mask(String plainOrEncryptedTckn) {
        if (plainOrEncryptedTckn == null || plainOrEncryptedTckn.isBlank()) {
            return plainOrEncryptedTckn;
        }
        String unmasked = decrypt(plainOrEncryptedTckn);
        if (unmasked.length() == TCKN_LENGTH) {
            return unmasked.substring(0, 3) + "*****" + unmasked.substring(8);
        }
        return unmasked.length() > 6 ? unmasked.substring(0, 3) + "******" : "******";
    }

    private String encryptLocal(String plainText) {
        String keyId = keyring.activeKeyId();
        try {
            byte[] initializationVector = new byte[GCM_IV_LENGTH_BYTES];
            secureRandom.nextBytes(initializationVector);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyring.activeKey(),
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector));
            cipher.updateAAD(additionalAuthenticatedData(keyId));

            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            ByteBuffer byteBuffer = ByteBuffer.allocate(initializationVector.length + cipherBytes.length);
            byteBuffer.put(initializationVector);
            byteBuffer.put(cipherBytes);

            return LOCAL_V2_PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception encryptionError) {
            log.error("AES-256-GCM encryption error under keyId={}: {}", keyId, encryptionError.getMessage(), encryptionError);
            throw new IllegalStateException("Failed to encrypt TCKN data at rest", encryptionError);
        }
    }

    private String decryptLocalV2(String cipherText) {
        String remainder = cipherText.substring(LOCAL_V2_PREFIX.length());
        int separatorIndex = remainder.indexOf(':');
        if (separatorIndex <= 0) {
            throw new IllegalStateException("Malformed enc:v2 TCKN ciphertext: no key id present");
        }

        String keyId = remainder.substring(0, separatorIndex);
        String base64Payload = remainder.substring(separatorIndex + 1);

        SecretKey key = keyring.keyFor(keyId).orElseThrow(() -> new IllegalStateException(
                ("This row was encrypted under TCKN key id '%s', which is not in the configured keyring. "
                        + "Restore it under app.security.encryption.keys.%s - a key that is still "
                        + "referenced by stored data must not be removed during rotation.")
                        .formatted(keyId, keyId)));

        return decryptGcm(base64Payload, key, additionalAuthenticatedData(keyId), "enc:v2 (keyId=" + keyId + ")");
    }

    private String decryptLegacyV1(String cipherText) {
        SecretKey legacyKey = keyring.legacyV1Key().orElseThrow(() -> new IllegalStateException(
                "This row is in the legacy enc:v1:gcm format, whose key is SHA-256(app.security.encryption"
                        + ".master-key), but no master-key is configured. Set it to the value in use when the "
                        + "row was written so it can be read and rewritten."));

        log.debug("Reading a legacy enc:v1 TCKN ciphertext; it will be rewritten as enc:v2 on next save.");
        return decryptGcm(cipherText.substring(LEGACY_V1_PREFIX.length()), legacyKey, null, "enc:v1:gcm");
    }

    private String decryptGcm(String base64Payload, SecretKey key, byte[] aad, String formatLabel) {
        try {
            byte[] encryptedBuffer = Base64.getDecoder().decode(base64Payload);

            ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedBuffer);
            byte[] initializationVector = new byte[GCM_IV_LENGTH_BYTES];
            byteBuffer.get(initializationVector);
            byte[] cipherBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherBytes);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector));
            if (aad != null) {
                cipher.updateAAD(aad);
            }

            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (Exception decryptionError) {
            log.error("AES-256-GCM decryption error for {} ciphertext: {}",
                    formatLabel, decryptionError.getMessage(), decryptionError);
            throw new IllegalStateException("Failed to decrypt encrypted TCKN ciphertext", decryptionError);
        }
    }

    /**
     * Binds the format version and key id into the GCM tag. A ciphertext therefore cannot be moved
     * to a different key id, or replayed as a different format version, without the tag check
     * failing - the key id in the payload is authenticated, not merely advisory.
     */
    private static byte[] additionalAuthenticatedData(String keyId) {
        return (LOCAL_V2_PREFIX + keyId).getBytes(StandardCharsets.UTF_8);
    }
}
