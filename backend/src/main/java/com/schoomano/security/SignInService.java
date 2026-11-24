package com.schoomano.security;

import com.schoomano.domain.auth.OtpPolicy;
import com.schoomano.domain.auth.SessionPolicy;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AppProperties;
import com.schoomano.platform.SmsSender;
import com.schoomano.platform.TenantTx;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Parent sign-in (ADR 0009). The rules live in {@link OtpPolicy} / {@link SessionPolicy}; the atomic bookkeeping
 * (resend gap, caps, attempt counting, rotation) lives in the SECURITY DEFINER functions of V7. This class only
 * generates and hashes secrets, calls those functions and turns their outcomes into API errors.
 *
 * Nothing here reveals whether a number belongs to a guardian: unknown numbers get the same responses, the same
 * limits and the same timing (the SMS is sent in the background), they just never receive a code.
 */
@Service
public class SignInService {

    private static final Logger log = LoggerFactory.getLogger(SignInService.class);
    /** Used only in dev mode when no pepper is configured. Never valid for a real deployment. */
    private static final String DEV_PEPPER = "schoomano-dev-only-pepper-do-not-use-in-production";

    private final TenantTx tx;
    private final Clock clock;
    private final AppProperties props;
    private final ObjectProvider<SmsSender> sms;
    private final SecureRandom random = new SecureRandom();
    private final ExecutorService smsQueue = Executors.newVirtualThreadPerTaskExecutor();

    public SignInService(TenantTx tx, Clock clock, AppProperties props, ObjectProvider<SmsSender> sms) {
        this.tx = tx;
        this.clock = clock;
        this.props = props;
        this.sms = sms;
    }

    @PreDestroy
    void stop() {
        smsQueue.shutdown();
    }

    public record TokenPair(String accessToken, int expiresIn, String refreshToken, int refreshExpiresIn) { }

    // ---- request a code -------------------------------------------------------------------------------------

    /** @return seconds to wait before asking again */
    public int requestCode(String phone, String clientAddress) {
        byte[] pepper = pepper();
        SmsSender sender = sms.getIfAvailable();
        if (sender == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "sms_unavailable",
                "Codes cannot be sent right now. Please try again later or contact the school office.");
        }
        String code = OtpPolicy.newCode(random);
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        var now = now();

        record Issue(String outcome, int retryAfter, Boolean deliverable) { }
        Issue r = tx.runWithoutTenant(jdbc -> jdbc.queryForObject(
            "select outcome, retry_after_seconds, deliverable from auth_otp_issue(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (rs, i) -> new Issue(rs.getString(1), rs.getInt(2), (Boolean) rs.getObject(3)),
            phone, hmac(pepper, "ip:" + clientAddress), salt, codeHash(pepper, salt, phone, code), now,
            OtpPolicy.TTL_SECONDS, OtpPolicy.RESEND_SECONDS, OtpPolicy.PER_NUMBER_PER_HOUR,
            OtpPolicy.PER_NUMBER_PER_DAY, OtpPolicy.PER_ADDRESS_PER_HOUR));

        switch (r.outcome()) {
            case "SENT" -> {
                if (Boolean.TRUE.equals(r.deliverable())) {
                    // In the background, so a known number does not answer more slowly than an unknown one.
                    smsQueue.submit(() -> {
                        try {
                            sender.sendOtp(phone, code);
                        } catch (RuntimeException e) {
                            log.error("sending a sign-in code failed: {}", e.getClass().getSimpleName());
                        }
                    });
                }
                return r.retryAfter();
            }
            case "TOO_SOON" -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp_resend_too_soon",
                "A code was sent a moment ago. You can ask for another in " + r.retryAfter() + " seconds.",
                Map.of("retryAfterSeconds", r.retryAfter()));
            default -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp_rate_limited",
                "Too many codes were requested. Please wait and try again later.",
                Map.of("retryAfterSeconds", r.retryAfter()));
        }
    }

    // ---- check the code and start a session -----------------------------------------------------------------

    public TokenPair verifyCode(String phone, String typedCode, String deviceLabel) {
        byte[] pepper = pepper();
        String code = OtpPolicy.normaliseTyped(typedCode);
        if (code == null) {
            throw ApiException.badRequest("invalid_code_format", "Enter the " + OtpPolicy.CODE_DIGITS + "-digit code from the SMS.");
        }
        var now = now();

        record Begin(String outcome, java.util.UUID id, byte[] salt, byte[] hash, Boolean deliverable) { }
        Begin b = tx.runWithoutTenant(jdbc -> jdbc.queryForObject(
            "select outcome, challenge_id, code_salt, code_hash, deliverable from auth_otp_begin_verify(?, ?, ?)",
            (rs, i) -> new Begin(rs.getString(1), rs.getObject(2, java.util.UUID.class), rs.getBytes(3), rs.getBytes(4),
                (Boolean) rs.getObject(5)),
            phone, now, OtpPolicy.MAX_ATTEMPTS));

        if ("LOCKED".equals(b.outcome())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "otp_locked",
                "Too many wrong codes. Ask for a new code.");
        }
        if (!"CHECK".equals(b.outcome())) {
            throw wrongCode();
        }
        // Always compute and compare, so an unknown number costs the same as a known one.
        boolean match = OtpPolicy.sameHash(b.hash(), codeHash(pepper, b.salt(), phone, code));
        if (!match || !Boolean.TRUE.equals(b.deliverable())) {
            throw wrongCode();
        }
        Boolean won = tx.runWithoutTenant(jdbc -> jdbc.queryForObject("select auth_otp_consume(?, ?)", Boolean.class, b.id(), now));
        if (!Boolean.TRUE.equals(won)) {
            throw wrongCode();
        }

        TokenPair pair = newPair();
        try {
            tx.runWithoutTenant(jdbc -> jdbc.queryForObject(
                "select auth_session_create(?, ?, ?, ?, ?, ?, ?)", java.util.UUID.class,
                phone, SessionTokens.hash(pair.accessToken()), SessionTokens.hash(pair.refreshToken()),
                SessionPolicy.cleanDeviceLabel(deviceLabel), now,
                SessionPolicy.ACCESS_TTL_SECONDS, SessionPolicy.REFRESH_TTL_SECONDS));
        } catch (DataAccessException e) {
            // The number stopped being on a guardian record between the code and the check.
            log.warn("session not created after a correct code: {}", e.getMostSpecificCause().getClass().getSimpleName());
            throw wrongCode();
        }
        return pair;
    }

    // ---- sessions -------------------------------------------------------------------------------------------

    /** Authenticates one request; empty when the token is unknown, expired or revoked. */
    public Optional<ExternalIdentity> authenticate(String accessToken) {
        if (!SessionTokens.isAccessToken(accessToken)) {
            return Optional.empty();
        }
        var now = now();
        return tx.runWithoutTenant(jdbc -> jdbc.query(
            "select issuer, subject from auth_session_resolve(?, ?)",
            (rs, i) -> new ExternalIdentity(rs.getString(1), rs.getString(2)),
            SessionTokens.hash(accessToken), now)).stream().findFirst();
    }

    public TokenPair refresh(String refreshToken) {
        if (!SessionTokens.isRefreshToken(refreshToken)) {
            throw invalidRefresh();
        }
        TokenPair pair = newPair();
        String outcome = tx.runWithoutTenant(jdbc -> jdbc.queryForObject(
            "select outcome from auth_session_refresh(?, ?, ?, ?, ?, ?)", String.class,
            SessionTokens.hash(refreshToken), SessionTokens.hash(pair.accessToken()), SessionTokens.hash(pair.refreshToken()),
            now(), SessionPolicy.ACCESS_TTL_SECONDS, SessionPolicy.REFRESH_TTL_SECONDS));
        if ("REUSED".equals(outcome)) {
            log.warn("a replaced refresh token was presented again; the session was revoked");
        }
        if (!"OK".equals(outcome)) {
            throw invalidRefresh();
        }
        return pair;
    }

    public void logout(String accessToken) {
        if (SessionTokens.isAccessToken(accessToken)) {
            tx.runWithoutTenant(jdbc -> jdbc.queryForObject("select auth_session_revoke(?, ?)", Boolean.class,
                SessionTokens.hash(accessToken), now()));
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private TokenPair newPair() {
        return new TokenPair(SessionTokens.newAccess(random), SessionPolicy.ACCESS_TTL_SECONDS,
            SessionTokens.newRefresh(random), SessionPolicy.REFRESH_TTL_SECONDS);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static ApiException wrongCode() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_code",
            "That code is not right or has expired. Check the SMS or ask for a new code.");
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_refresh_token", "Please sign in again.");
    }

    private byte[] pepper() {
        String configured = props.auth() == null ? null : props.auth().otpPepper();
        if (configured != null && !configured.isBlank()) {
            if (configured.length() < 16) {
                throw new IllegalStateException("schoomano.auth.otp-pepper must be at least 16 characters");
            }
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        if (props.auth() != null && props.auth().devMode()) {
            return DEV_PEPPER.getBytes(StandardCharsets.UTF_8);
        }
        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "sign_in_not_configured",
            "Phone sign-in is not set up on this server yet.");
    }

    /** HMAC over salt, number and code: the code alone is only 10^6 possibilities, so the pepper is what protects it. */
    private static byte[] codeHash(byte[] pepper, byte[] salt, String phone, String code) {
        byte[] head = (phone + "\0" + code).getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[salt.length + 1 + head.length];
        System.arraycopy(salt, 0, data, 0, salt.length);
        data[salt.length] = 0;
        System.arraycopy(head, 0, data, salt.length + 1, head.length);
        return hmac(pepper, data);
    }

    private static byte[] hmac(byte[] key, String text) {
        return hmac(key, text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is required", e);
        }
    }
}
