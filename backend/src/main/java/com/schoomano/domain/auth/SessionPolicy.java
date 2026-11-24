package com.schoomano.domain.auth;

/**
 * Lifetimes of a parent session (ADR 0009: long-lived on mobile, refresh tokens rotate). The access token is
 * short so a stolen one dies quickly; the refresh token is what keeps a phone signed in, and it is replaced on
 * every use.
 */
public final class SessionPolicy {

    public static final int ACCESS_TTL_SECONDS = 30 * 60;
    public static final int REFRESH_TTL_SECONDS = 30 * 24 * 60 * 60;
    public static final int MAX_DEVICE_LABEL = 80;

    private SessionPolicy() {
    }

    /** A device name for the "signed in on" list: trimmed, control characters removed, at most 80 characters. */
    public static String cleanDeviceLabel(String label) {
        if (label == null) {
            return null;
        }
        String cleaned = label.replaceAll("\\p{Cntrl}", " ").trim().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() <= MAX_DEVICE_LABEL ? cleaned : cleaned.substring(0, MAX_DEVICE_LABEL);
    }
}
