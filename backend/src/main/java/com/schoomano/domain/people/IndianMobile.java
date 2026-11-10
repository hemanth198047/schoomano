package com.schoomano.domain.people;

import java.util.Optional;

/**
 * Indian mobile numbers as parents type them ("98450 12345", "+91-98450-12345", "098450 12345", "919845012345"),
 * normalised to E.164 (+919845012345). Mobile numbers start with 6, 7, 8 or 9 and have ten digits.
 * Note: an 11-digit landline written with its STD code (e.g. 080 2345 6789) is indistinguishable from a
 * mobile written with a leading 0, so the OTP check (B4) is what finally proves a number is a mobile.
 */
public final class IndianMobile {

    private IndianMobile() {
    }

    public static Optional<String> normalise(String input) {
        if (input == null) {
            return Optional.empty();
        }
        String digits = input.replaceAll("[\\s().-]", "");
        if (digits.startsWith("+")) {
            if (!digits.startsWith("+91")) {
                return Optional.empty();
            }
            digits = digits.substring(3);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits.matches("^[6-9][0-9]{9}$") ? Optional.of("+91" + digits) : Optional.empty();
    }

    /** "+919845012345" → "+91 98450 12345" for display. */
    public static String display(String e164) {
        if (e164 == null || !e164.matches("^\\+91[0-9]{10}$")) {
            return e164;
        }
        return "+91 " + e164.substring(3, 8) + " " + e164.substring(8);
    }
}
