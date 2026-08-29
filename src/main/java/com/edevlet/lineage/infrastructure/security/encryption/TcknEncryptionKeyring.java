package com.edevlet.lineage.infrastructure.security.encryption;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The set of keys this service can decrypt with, and the one it encrypts with.
 *
 * <p>Derivation is PBKDF2-HMAC-SHA256 over the configured secret, salted per key id, run once at
 * startup. Deriving per key id rather than once globally means two keys configured with the same
 * passphrase by accident still produce different AES keys, and it makes the key id a real part of
 * the derivation rather than a label attached afterwards.
 *
 * <p>Also holds the legacy key: {@code SHA-256(master-key)}, which is what the pre-keyring code
 * derived and therefore the only thing that can read a {@code enc:v1:gcm:} row. It is never used to
 * encrypt. Its weakness is the reason the format was replaced; keeping it read-only is what lets
 * existing data be read and rewritten instead of being abandoned.
 *
 * <p>Every failure here is a startup failure, deliberately. A misconfigured keyring that boots is a
 * service that writes rows nobody can read later.
 */
@Slf4j
public final class TcknEncryptionKeyring {

    /** Key ids travel inside ciphertext, where {@code :} is the field separator. */
    private static final Pattern VALID_KEY_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private static final int DERIVED_KEY_BITS = 256;

    private final String activeKeyId;
    private final Map<String, SecretKey> keysById;
    private final SecretKey legacyMasterKey;

    private TcknEncryptionKeyring(String activeKeyId, Map<String, SecretKey> keysById, SecretKey legacyMasterKey) {
        this.activeKeyId = activeKeyId;
        this.keysById = Map.copyOf(keysById);
        this.legacyMasterKey = legacyMasterKey;
    }

    public static TcknEncryptionKeyring from(TcknEncryptionProperties properties) {
        String activeKeyId = properties.getActiveKeyId();
        if (activeKeyId == null || activeKeyId.isBlank()) {
            activeKeyId = TcknEncryptionProperties.DEFAULT_ACTIVE_KEY_ID;
        }
        requireValidKeyId(activeKeyId);

        Map<String, String> configuredSecrets = resolveSecrets(properties, activeKeyId);
        if (!configuredSecrets.containsKey(activeKeyId)) {
            throw new IllegalStateException(
                    ("app.security.encryption.active-key-id is '%s', but no secret is configured for it. "
                            + "Set app.security.encryption.keys.%s, or app.security.encryption.master-key "
                            + "for a single-key setup.").formatted(activeKeyId, activeKeyId));
        }

        int iterations = resolveIterations(properties.getKdf().getIterations());
        String salt = properties.getKdf().getSalt();
        if (salt == null || salt.isBlank()) {
            throw new IllegalStateException(
                    "app.security.encryption.kdf.salt must be set; it is not secret, but it must be stable "
                            + "per environment because changing it re-derives every key.");
        }

        Map<String, SecretKey> derived = new LinkedHashMap<>();
        configuredSecrets.forEach((keyId, secret) -> {
            requireValidKeyId(keyId);
            derived.put(keyId, derivePbkdf2Key(secret, salt, keyId, iterations));
        });

        SecretKey legacyKey = hasText(properties.getMasterKey())
                ? deriveLegacySha256Key(properties.getMasterKey())
                : null;

        log.info("Initialised TCKN encryption keyring: activeKeyId={}, decryptableKeyIds={}, "
                        + "kdf=PBKDF2WithHmacSHA256/{} iterations, legacyV1ReadKey={}",
                activeKeyId, derived.keySet(), iterations, legacyKey != null ? "present" : "absent");

        return new TcknEncryptionKeyring(activeKeyId, derived, legacyKey);
    }

    /**
     * {@code keys} wins when present. {@code master-key} still seeds the active id so a deployment
     * that only ever set the single legacy property keeps working without any config change.
     */
    private static Map<String, String> resolveSecrets(TcknEncryptionProperties properties, String activeKeyId) {
        Map<String, String> secrets = new LinkedHashMap<>();
        if (hasText(properties.getMasterKey())) {
            secrets.put(activeKeyId, properties.getMasterKey());
        }
        properties.getKeys().forEach((keyId, secret) -> {
            if (hasText(secret)) {
                secrets.put(keyId, secret);
            }
        });
        if (secrets.isEmpty()) {
            throw new IllegalStateException(
                    "No TCKN encryption key is configured. Set app.security.encryption.master-key "
                            + "(single key) or app.security.encryption.keys.<id> (keyring). Refusing to start "
                            + "rather than persist national identity numbers under a key nobody chose.");
        }
        return secrets;
    }

    private static int resolveIterations(int configured) {
        if (configured < TcknEncryptionProperties.KeyDerivation.MINIMUM_ITERATIONS) {
            log.warn("app.security.encryption.kdf.iterations is {}, below the {} floor; using the floor instead.",
                    configured, TcknEncryptionProperties.KeyDerivation.MINIMUM_ITERATIONS);
            return TcknEncryptionProperties.KeyDerivation.MINIMUM_ITERATIONS;
        }
        return configured;
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public SecretKey activeKey() {
        return keysById.get(activeKeyId);
    }

    /**
     * Empty when the ciphertext names a key this deployment no longer carries. The caller turns
     * that into a loud failure rather than a fallback attempt with some other key: guessing would
     * either fail the GCM tag anyway or, worse, succeed against the wrong key.
     */
    public Optional<SecretKey> keyFor(String keyId) {
        return Optional.ofNullable(keysById.get(keyId));
    }

    public Optional<SecretKey> legacyV1Key() {
        return Optional.ofNullable(legacyMasterKey);
    }

    private static void requireValidKeyId(String keyId) {
        if (keyId == null || !VALID_KEY_ID.matcher(keyId).matches()) {
            throw new IllegalStateException(
                    ("Invalid TCKN encryption key id '%s'. Key ids are stored inside ciphertext and must "
                            + "match [A-Za-z0-9_-]{1,32}.").formatted(keyId));
        }
    }

    private static SecretKey derivePbkdf2Key(String secret, String salt, String keyId, int iterations) {
        try {
            byte[] perKeySalt = MessageDigest.getInstance("SHA-256")
                    .digest((salt + "|" + keyId).getBytes(StandardCharsets.UTF_8));
            PBEKeySpec keySpec = new PBEKeySpec(secret.toCharArray(), perKeySalt, iterations, DERIVED_KEY_BITS);
            byte[] keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(keySpec)
                    .getEncoded();
            keySpec.clearPassword();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception derivationFailure) {
            throw new IllegalStateException(
                    "Failed to derive the TCKN encryption key for key id " + keyId, derivationFailure);
        }
    }

    private static SecretKey deriveLegacySha256Key(String secret) {
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception derivationFailure) {
            throw new IllegalStateException("Failed to derive the legacy v1 TCKN read key", derivationFailure);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
