package com.schoomano.core.access;

import com.schoomano.core.tenancy.TenantId;
import java.time.Instant;

/** Platform support entering a tenant under a tenant-approved, time-limited grant (M30). */
public record SupportSession(TenantId tenant, Instant startsAt, Instant expiresAt, Instant revokedAt) {
    public boolean isActiveAt(Instant now) {
        return revokedAt == null && !now.isBefore(startsAt) && now.isBefore(expiresAt);
    }
}
