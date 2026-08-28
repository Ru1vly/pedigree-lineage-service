package com.edevlet.lineage;

import com.edevlet.lineage.infrastructure.util.TcknValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class TcknValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"12345678950", "10000000146", "10000000382"})
    @DisplayName("Valid TCKN checksums should pass validation")
    void testValidTckn(String tckn) {
        assertTrue(TcknValidator.isValid(tckn), "Expected valid TCKN: " + tckn);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "10000000000", // Invalid 10th and 11th check digits
            "02345678950", // Starts with 0
            "1234567895",  // 10 digits (too short)
            "123456789500",// 12 digits (too long)
            "1234567895A", // Non-numeric
            ""
    })
    @DisplayName("Invalid TCKN patterns should fail validation")
    void testInvalidTckn(String tckn) {
        assertFalse(TcknValidator.isValid(tckn), "Expected invalid TCKN: " + tckn);
    }

    @Test
    @DisplayName("Null TCKN should return false")
    void testNullTckn() {
        assertFalse(TcknValidator.isValid(null));
    }
}
