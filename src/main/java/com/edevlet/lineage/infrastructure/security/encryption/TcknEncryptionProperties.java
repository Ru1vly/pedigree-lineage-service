package com.edevlet.lineage.infrastructure.security.encryption;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for TCKN field-level encryption at rest.
 *
 * <p>The single {@code master-key} property this replaced could not be rotated. Ciphertexts carried
 * a {@code enc:v1:gcm:} prefix that looked like a version but named no key, so nothing stored
 * alongside a row said which key had encrypted it. Changing {@code TCKN_ENCRYPTION_MASTER_KEY}
 * therefore did not rotate anything - it orphaned every historical row, permanently, and every
 * subsequent read of one threw.
 *
 * <p>A keyring fixes that. {@link #keys} maps a key id to its secret and every listed key can
 * decrypt; {@link #activeKeyId} names the one that encrypts. Rotation is: add the new secret under
 * a new id, point {@code active-key-id} at it, deploy, leave the old entry in place until the last
 * row encrypted under it has been rewritten. See {@link TcknEncryptionKeyring}.
 *
 * <p>{@code master-key} is still honoured, for two reasons: it is what existing deployments and
 * {@code values-prod.yaml} set, and it is the only way to read rows written in the old
 * {@code enc:v1:gcm:} format, whose key was {@code SHA-256(master-key)}.
 */
@Component
@ConfigurationProperties(prefix = "app.security.encryption")
@Getter
@Setter
public class TcknEncryptionProperties {

    public static final String DEFAULT_ACTIVE_KEY_ID = "primary";

    /**
     * Secret backing the default key, and the only key that can read legacy {@code enc:v1:gcm:}
     * rows. When {@link #keys} is empty this becomes the sole entry in the keyring, registered
     * under {@link #activeKeyId}.
     */
    private String masterKey = "";

    /** Id of the key new ciphertexts are written under. Must exist in the resolved keyring. */
    private String activeKeyId = DEFAULT_ACTIVE_KEY_ID;

    /**
     * Key id to secret. Every entry can decrypt; only {@link #activeKeyId} encrypts. Ids appear
     * verbatim inside ciphertext, so they are restricted to {@code [A-Za-z0-9_-]}.
     */
    private Map<String, String> keys = new LinkedHashMap<>();

    private final KeyDerivation kdf = new KeyDerivation();

    /**
     * Key derivation parameters.
     *
     * <p>The key used to be {@code SHA-256(passphrase)}: one unsalted, uniterated hash of a string
     * out of a config file. That is a fine way to turn a passphrase into 32 bytes and a bad way to
     * derive a key, because it costs an attacker holding a database dump exactly one hash per
     * guess, and the same guess is reusable against every deployment that picked the same
     * passphrase. PBKDF2-HMAC-SHA256 with a per-deployment salt and a real iteration count is the
     * cheapest correct answer here; it runs once per key at startup, not per row.
     */
    @Getter
    @Setter
    public static class KeyDerivation {

        /** Below this, iterations are clamped up rather than honoured. */
        public static final int MINIMUM_ITERATIONS = 100_000;

        /**
         * The shipped salt, so a fresh clone boots. {@code SecretsConfigurationGuard} refuses to
         * start under the "production" profile while this is still the configured value.
         */
        public static final String INSECURE_DEFAULT_SALT = "pedigree-lineage-tckn-kdf-salt";

        /**
         * Per-deployment KDF salt. Not a secret - a salt's job is to make one attacker's
         * precomputation useless against everyone else's data, which it does in the clear. It must
         * still be set per environment and must never change once rows exist, because changing it
         * changes every derived key and orphans them exactly as rotating the old master key did.
         */
        private String salt = INSECURE_DEFAULT_SALT;

        private int iterations = 210_000;
    }
}
