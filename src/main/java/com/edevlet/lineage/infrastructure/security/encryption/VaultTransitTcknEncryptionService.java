package com.edevlet.lineage.infrastructure.security.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.vault.core.VaultOperations;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Implementation of Field-Level Data Encryption at Rest using HashiCorp Vault Transit Engine
 * with an AES-256-GCM Envelope Encryption fallback for offline / test environments.
 * Ensures PostgreSQL DBAs cannot read unmasked citizen TCKN numbers.
 */
@Slf4j
@Service
public class VaultTransitTcknEncryptionService implements TcknEncryptionService {

    private static final String VAULT_PREFIX = "vault:v1:";
    private static final String ENVELOPE_PREFIX = "enc:v1:gcm:";
    private static final String TRANSIT_KEY_NAME = "tckn-key";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;

    private final VaultOperations vaultOperations;
    private final boolean vaultEnabled;
    private final SecretKey envelopeMasterKey;

    @Autowired
    public VaultTransitTcknEncryptionService(
            @Autowired(required = false) VaultOperations vaultOperations,
            @Value("${app.security.vault.enabled:false}") boolean vaultEnabled,
            @Value("${app.security.encryption.master-key:edevlet-tckn-field-level-encryption-master-secret-key-2026}") String masterSecretKey) {
        this.vaultOperations = vaultOperations;
        this.vaultEnabled = vaultEnabled;
        this.envelopeMasterKey = deriveKey(masterSecretKey);
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

        return encryptEnvelope(plainTckn);
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
            log.warn("Vault Transit Engine encryption call failed, using AES-256-GCM envelope fallback: {}", vaultException.getMessage());
        }
        return null;
    }

    @Override
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank() || !isEncrypted(cipherText)) {
            return cipherText;
        }

        if (cipherText.startsWith(VAULT_PREFIX)) {
            return decryptVaultCiphertext(cipherText);
        }

        if (cipherText.startsWith(ENVELOPE_PREFIX)) {
            return decryptEnvelope(cipherText);
        }

        // Hard failure so unrecognized formats never silently degrade to passthrough
        throw new IllegalStateException("Unrecognised TCKN ciphertext envelope");
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
        return text.startsWith(VAULT_PREFIX) || text.startsWith(ENVELOPE_PREFIX);
    }

    @Override
    public String mask(String plainOrEncryptedTckn) {
        if (plainOrEncryptedTckn == null || plainOrEncryptedTckn.isBlank()) {
            return plainOrEncryptedTckn;
        }
        String unmasked = decrypt(plainOrEncryptedTckn);
        if (unmasked.length() == 11) {
            return unmasked.substring(0, 3) + "*****" + unmasked.substring(8);
        }
        return unmasked.length() > 6 ? unmasked.substring(0, 3) + "******" : "******";
    }

    private String encryptEnvelope(String plainText) {
        try {
            byte[] initializationVector = new byte[GCM_IV_LENGTH_BYTES];
            SecureRandom random = new SecureRandom();
            random.nextBytes(initializationVector);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector);
            cipher.init(Cipher.ENCRYPT_MODE, envelopeMasterKey, parameterSpec);

            byte[] cipherBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            ByteBuffer byteBuffer = ByteBuffer.allocate(initializationVector.length + cipherBytes.length);
            byteBuffer.put(initializationVector);
            byteBuffer.put(cipherBytes);

            return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception encryptionError) {
            log.error("Envelope AES-256-GCM encryption error: {}", encryptionError.getMessage(), encryptionError);
            throw new IllegalStateException("Failed to encrypt TCKN data at rest", encryptionError);
        }
    }

    private String decryptEnvelope(String cipherText) {
        try {
            String base64Payload = cipherText.substring(ENVELOPE_PREFIX.length());
            byte[] encryptedBuffer = Base64.getDecoder().decode(base64Payload);

            ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedBuffer);
            byte[] initializationVector = new byte[GCM_IV_LENGTH_BYTES];
            byteBuffer.get(initializationVector);
            byte[] cipherBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherBytes);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, initializationVector);
            cipher.init(Cipher.DECRYPT_MODE, envelopeMasterKey, parameterSpec);

            byte[] plainBytes = cipher.doFinal(cipherBytes);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (Exception decryptionError) {
            log.error("Envelope AES-256-GCM decryption error: {}", decryptionError.getMessage(), decryptionError);
            throw new IllegalStateException("Failed to decrypt encrypted TCKN ciphertext", decryptionError);
        }
    }

    private static SecretKey deriveKey(String secret) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha256.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive SHA-256 encryption key", e);
        }
    }
}
