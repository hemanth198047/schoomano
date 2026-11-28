package com.schoomano.security;

import com.schoomano.platform.TenantTx;
import org.springframework.stereotype.Service;

/** Thin wrapper over the idem_* database functions. A caller is identified by a hash, never by the raw identity. */
@Service
public class IdempotencyService {

    /** NEW, REPLAY, MISMATCH or BUSY, with the stored answer for a replay. */
    public record Begin(String outcome, Integer status, String body, String contentType) { }

    private final TenantTx tx;

    public IdempotencyService(TenantTx tx) {
        this.tx = tx;
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
