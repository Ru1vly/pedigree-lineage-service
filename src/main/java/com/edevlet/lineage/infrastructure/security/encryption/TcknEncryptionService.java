package com.edevlet.lineage.infrastructure.security.encryption;

/**
 * Service interface for Field-Level Data Encryption at Rest for citizen national identity numbers (TCKN).
 * Guarantees raw TCKN data is encrypted prior to database storage in PostgreSQL, preventing DBAs
 * and raw database access from exposing unmasked identity details.
 */
public interface TcknEncryptionService {

    /**
     * Encrypts a raw 11-digit TCKN into a secure ciphertext payload.
     *
     * @param plainTckn Raw unmasked 11-digit TCKN number.
     * @return Encrypted ciphertext string (e.g., vault:v1:... or enc:v1:gcm:...).
     */
    String encrypt(String plainTckn);

    /**
     * Decrypts ciphertext back to the original 11-digit unmasked TCKN number.
     *
     * @param cipherText Encrypted ciphertext string.
     * @return Plaintext 11-digit TCKN number.
     */
    String decrypt(String cipherText);

    /**
     * Checks if a string is already encrypted.
     *
     * @param text Text value to inspect.
     * @return true if ciphertext, false if raw unencrypted TCKN.
     */
    boolean isEncrypted(String text);

    /**
     * Masks TCKN for logging and display purposes (e.g. 123*****950).
     *
     * @param plainOrEncryptedTckn Raw or encrypted TCKN.
     * @return Masked TCKN string.
     */
    String mask(String plainOrEncryptedTckn);
}
