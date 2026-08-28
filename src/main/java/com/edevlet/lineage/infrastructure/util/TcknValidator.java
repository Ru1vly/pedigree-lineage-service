package com.edevlet.lineage.infrastructure.util;

public final class TcknValidator {

    private TcknValidator() {}

    public static boolean isValid(String tckn) {
        if (!hasValidFormat(tckn)) {
            return false;
        }

        int[] digits = new int[11];
        for (int i = 0; i < 11; i++) {
            digits[i] = Character.getNumericValue(tckn.charAt(i));
        }

        int oddPositionSum = digits[0] + digits[2] + digits[4] + digits[6] + digits[8];
        int evenPositionSum = digits[1] + digits[3] + digits[5] + digits[7];

        // In Java, negative operands with % can yield negative remainders; adjust to stay non-negative
        int expectedTenthDigit = ((oddPositionSum * 7) - evenPositionSum) % 10;
        if (expectedTenthDigit < 0) {
            expectedTenthDigit += 10;
        }
        if (expectedTenthDigit != digits[9]) {
            return false;
        }

        int sumFirstTenDigits = 0;
        for (int i = 0; i < 10; i++) {
            sumFirstTenDigits += digits[i];
        }

        int expectedEleventhDigit = sumFirstTenDigits % 10;
        return expectedEleventhDigit == digits[10];
    }

    private static boolean hasValidFormat(String tckn) {
        return tckn != null && tckn.length() == 11 && tckn.matches("^[1-9][0-9]{10}$");
    }
}
