package com.schoomano.domain.auth;

import java.security.MessageDigest;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * The parent sign-in code rules from ADR 0009, in one place. The database functions in V7 do the atomic
 * bookkeeping (resend gap, caps, attempt counting) but receive every number from here, so tightening a limit
 * is one edit and one test.
 */
public final class OtpPolicy {

    public static final int CODE_DIGITS = 6;
    public static final int TTL_SECONDS = 5 * 60;
    public static final int RESEND_SECONDS = 30;
    public static final int MAX_ATTEMPTS = 5;
    public static final int PER_NUMBER_PER_HOUR = 5;
    /** Caps the SMS bill if someone pumps one number all day (SMS-pumping defence). */
    public static final int PER_NUMBER_PER_DAY = 10;
    public static final int PER_ADDRESS_PER_HOUR = 20;

    private OtpPolicy() {
    }

    /** A uniformly random code, zero padded ("004217"). Pass a SecureRandom in production. */
    public static String newCode(RandomGenerator random) {
        return String.format(Locale.ROOT, "%0" + CODE_DIGITS + "d", random.nextInt((int) Math.pow(10, CODE_DIGITS)));
    }

    /** What users type: digits only, with spaces tolerated ("123 456"). Returns null when it cannot be a code. */
    public static String normaliseTyped(String typed) {
        if (typed == null) {
            return null;
        }
        String digits = typed.replaceAll("[\\s-]", "");
        return digits.matches("^[0-9]{" + CODE_DIGITS + "}$") ? digits : null;
    }

    /** Constant-time comparison of two hashes, so response timing does not leak how many bytes matched. */
    public static boolean sameHash(byte[] expected, byte[] actual) {
        return expected != null && actual != null && MessageDigest.isEqual(expected, actual);
    }
}
