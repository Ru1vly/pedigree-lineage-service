package com.edevlet.lineage.infrastructure.util;

/**
 * Single home for TCKN log masking, which previously existed as a copy in
 * LineagePipelinePhaseRunner and another in LegacyCensusGraphClientImpl.
 *
 * <p>Both copies returned the literal {@code "123*****901"} when handed null or a short value.
 * That is a well-formed, entirely plausible masked national ID for a citizen who does not exist,
 * written into logs and into the {@code rootPerson} of stored ancestry results - the same move
 * this codebase rejects in docs/WHAT_WAS_BROKEN.md section 4, where a census-backend fallback
 * invented ancestors that downstream code could not tell from real ones. A masked identity is
 * still an identity; a placeholder that looks like one is a fabricated record.
 *
 * <p>The markers below are deliberately not mistakable for a masked TCKN. An operator reading
 * {@code [absent]} in a log knows the value was missing; one reading {@code 123*****901} does not.
 */
public final class NationalIdMasker {

    public static final String ABSENT = "[absent]";
    public static final String MALFORMED = "[malformed]";

    private static final int TCKN_LENGTH = 11;

    private NationalIdMasker() {}

    /**
     * Masks a TCKN for logging and for storage in result payloads: first three digits, then the
     * middle five replaced, then the last three. Returns an explicit marker - never a synthetic
     * identity - when there is nothing valid to mask.
     */
    public static String mask(String nationalId) {
        if (nationalId == null || nationalId.isBlank()) {
            return ABSENT;
        }
        if (nationalId.length() != TCKN_LENGTH) {
            return MALFORMED;
        }
        return nationalId.substring(0, 3) + "*****" + nationalId.substring(8);
    }
}
