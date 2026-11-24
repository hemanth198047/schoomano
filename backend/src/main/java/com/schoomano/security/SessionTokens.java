package com.schoomano.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Opaque bearer tokens: 256 random bits, shown to the client once and stored only as a SHA-256 hash (so a leaked
 * database does not leak sessions). The prefix lets the filters tell a Schoomano session apart from an OIDC JWT.
 */
final class SessionTokens {

    static final String ACCESS_PREFIX = "smo_";
    static final String REFRESH_PREFIX = "smr_";
    private static final Pattern ACCESS = Pattern.compile("^smo_[A-Za-z0-9_-]{43}$");
    private static final Pattern REFRESH = Pattern.compile("^smr_[A-Za-z0-9_-]{43}$");

    private SessionTokens() {
    }

    static String newAccess(SecureRandom random) {
        return ACCESS_PREFIX + random(random);
    }

    static String newRefresh(SecureRandom random) {
        return REFRESH_PREFIX + random(random);
    }

    static boolean isAccessToken(String token) {
        return token != null && ACCESS.matcher(token).matches();
    }

    static boolean isRefreshToken(String token) {
        return token != null && REFRESH.matcher(token).matches();
    }

    static byte[] hash(String token) {
        return sha256(token.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static String random(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
