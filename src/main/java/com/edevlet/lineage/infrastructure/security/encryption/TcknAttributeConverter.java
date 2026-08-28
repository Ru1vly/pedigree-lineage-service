package com.edevlet.lineage.infrastructure.security.encryption;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter for Field-Level Data Encryption at Rest.
 * Intercepts raw TCKN fields on database writes to store encrypted ciphertexts in PostgreSQL,
 * ensuring DBAs querying database tables cannot view raw citizen identity data.
 * Automatically decrypts stored ciphertext into plaintext TCKNs upon entity load.
 */
@Slf4j
@Component
@Converter
public class TcknAttributeConverter implements AttributeConverter<String, String> {

    private static TcknEncryptionService encryptionService;

    public TcknAttributeConverter() {
    }

    @Autowired
    public TcknAttributeConverter(TcknEncryptionService service) {
        setEncryptionService(service);
    }

    public static synchronized void setEncryptionService(TcknEncryptionService service) {
        TcknAttributeConverter.encryptionService = service;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isBlank()) {
            return attribute;
        }
        if (encryptionService == null) {
            // Fail closed: a converter whose entire purpose is "never store this unencrypted"
            // must not silently degrade to plaintext just because Spring hasn't finished wiring
            // it yet (AttributeConverters are instantiated by Hibernate, not Spring, so this
            // static field can theoretically still be unset the first time a converter method
            // runs). Refusing the write is the safe failure mode here, not a warning log.
            throw new IllegalStateException(
                    "TcknEncryptionService is not available yet; refusing to persist a national ID unencrypted.");
        }
        return encryptionService.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return dbData;
        }
        if (encryptionService == null) {
            throw new IllegalStateException(
                    "TcknEncryptionService is not available yet; refusing to return a national ID without attempting decryption.");
        }
        return encryptionService.decrypt(dbData);
    }
}
