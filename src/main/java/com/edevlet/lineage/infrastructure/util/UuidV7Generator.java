package com.edevlet.lineage.infrastructure.util;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

public final class UuidV7Generator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7Generator() {}

    public static UUID generate() {
        long timestamp = Instant.now().toEpochMilli();
        long mostSignificantBits = (timestamp << 16) | 0x7000L | (RANDOM.nextLong() & 0x0FFFL);
        long leastSignificantBits = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(mostSignificantBits, leastSignificantBits);
    }

    public static String generateString() {
        return generate().toString();
    }
}
