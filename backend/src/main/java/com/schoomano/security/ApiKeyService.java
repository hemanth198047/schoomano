package com.schoomano.security;

import com.schoomano.platform.TenantTx;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Issues and recognises integration API keys ("smk_..."). Only the SHA-256 hash of a key is ever stored. */
@Service
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** A new key: the secret is shown to the person once; only the hash and the last four characters are kept. */
    public record Issued(String secret, byte[] hash, String hint) { }

    private final TenantTx tx;

    public ApiKeyService(TenantTx tx) {
        this.tx = tx;
    }

    public Issued issue() {
        String secret = SessionTokens.newApiKey(RANDOM);
        return new Issued(secret, SessionTokens.hash(secret), secret.substring(secret.length() - 4));
    }

    /** The identity behind a live key, or empty when the key is unknown, revoked or expired. */
    public Optional<ExternalIdentity> authenticate(String token) {
        if (!SessionTokens.isApiKey(token)) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return tx.runWithoutTenant(jdbc -> jdbc.query("select issuer, subject from api_key_resolve(?, ?)",
            (rs, i) -> new ExternalIdentity(rs.getString(1), rs.getString(2)), SessionTokens.hash(token), now)).stream().findFirst();
    }
}
