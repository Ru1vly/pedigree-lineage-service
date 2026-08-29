package com.edevlet.lineage;

import com.edevlet.lineage.infrastructure.util.NationalIdMasker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class NationalIdMaskerTest {

    @Test
    @DisplayName("A valid TCKN keeps its first and last three digits")
    void validTckn_isMasked() {
        assertThat(NationalIdMasker.mask("12345678950")).isEqualTo("123*****950");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("An absent value is reported as absent, never as a plausible identity")
    void absentValue_isNotFabricated(String input) {
        String masked = NationalIdMasker.mask(input);

        assertThat(masked).isEqualTo(NationalIdMasker.ABSENT);
        // The old implementations returned the literal "123*****901" here - a well-formed masked
        // national ID for a citizen who does not exist, written into logs and into the rootPerson
        // of stored ancestry results. A masked identity is still an identity.
        assertThat(masked).doesNotContain("*****");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "1234567895", "123456789501"})
    @DisplayName("A wrong-length value is reported as malformed rather than silently truncated")
    void malformedValue_isNotFabricated(String input) {
        assertThat(NationalIdMasker.mask(input)).isEqualTo(NationalIdMasker.MALFORMED);
    }

    @Test
    @DisplayName("The masked form never contains the five middle digits")
    void maskedForm_hidesTheMiddle() {
        assertThat(NationalIdMasker.mask("12345678950")).doesNotContain("45678");
    }
}
