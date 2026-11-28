package com.schoomano.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.TenantTx;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Thin wrapper over the idem_* database functions. A caller is identified by a hash, never by the raw identity. */
@Service
public class IdempotencyService {

    /** NEW, REPLAY, MISMATCH or BUSY, with the stored answer for a replay. */
    public record Begin(String outcome, Integer status, String body, String contentType) { }

    /** What a keyed action gave back, and whether it was the stored answer of an earlier identical request. */
    public record Once<T>(T value, boolean replayed) { }

    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");

    private final TenantTx tx;
    private final ObjectMapper json;

    public IdempotencyService(TenantTx tx, ObjectMapper json) {
        this.tx = tx;
        this.json = json;
    }

    /**
     * Idempotency for an endpoint the filter cannot wrap (a file upload, whose body must stay with the container). The
     * first call with a key does the work and keeps its JSON answer for 24 hours; the same key with the same content gets
     * that answer again and does nothing; the same key with other content is refused (422); a repeat while the first
     * is still running is refused (409). Work that fails is forgotten so the client can retry with the same key.
     * Without a key the work simply runs.
     */
    public <T> Once<T> once(String key, UUID person, UUID tenant, byte[] fingerprint, Class<T> type, Supplier<T> work) {
        if (key == null) {
            return new Once<>(work.get(), false);
        }
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("invalid_idempotency_key", "Use 8 to 100 letters, digits, dots, dashes, colons or underscores for the Idempotency-Key.");
        }
        String scope = HexFormat.of().formatHex(sha256(("upload|" + person + "|" + tenant).getBytes(StandardCharsets.UTF_8)));
        Begin begin = begin(scope, key, sha256(fingerprint));
        switch (begin.outcome()) {
            case "REPLAY" -> {
                try {
                    return new Once<>(json.readValue(begin.body(), type), true);
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("stored answer cannot be read", e);
                }
            }
            case "MISMATCH" -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused", "This Idempotency-Key was already used for a different request.");
            case "BUSY" -> throw new ApiException(HttpStatus.CONFLICT, "request_in_progress", "The first request with this Idempotency-Key is still running. Try again shortly.");
            default -> { }
        }
        byte[] stored = sha256(fingerprint);
        boolean kept = false;
        try {
            T value = work.get();
            finish(scope, key, stored, 201, json.writeValueAsString(value), "application/json");
            kept = true;
            return new Once<>(value, false);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("answer cannot be stored", e);
        } finally {
            if (!kept) {
                abandon(scope, key, stored);
            }
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    public Begin begin(String scope, String key, byte[] fingerprint) {
        return tx.runWithoutTenant(jdbc -> jdbc.queryForObject(
            "select outcome, r_status, r_body, r_content_type from idem_begin(?, ?, ?)",
            (rs, i) -> new Begin(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getString(4)), scope, key, fingerprint));
    }

    public void finish(String scope, String key, byte[] fingerprint, int status, String body, String contentType) {
        tx.runWithoutTenant(jdbc -> jdbc.queryForObject("select idem_finish(?, ?, ?, ?, ?, ?)", Boolean.class,
            scope, key, fingerprint, status, body, contentType));
    }

    public void abandon(String scope, String key, byte[] fingerprint) {
        tx.runWithoutTenant(jdbc -> jdbc.queryForList("select idem_abandon(?, ?, ?)", scope, key, fingerprint));
    }

}
