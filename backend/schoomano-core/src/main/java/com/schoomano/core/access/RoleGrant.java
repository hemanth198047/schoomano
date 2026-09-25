package com.schoomano.core.access;

import com.schoomano.core.tenancy.ScopeNode;
import java.time.Instant;
import java.util.Objects;

public record RoleGrant(String roleKey, ScopeNode scope, Instant validFrom, Instant validTo) {
    public RoleGrant {
        Objects.requireNonNull(roleKey, "roleKey");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(validFrom, "validFrom");
    }

    public boolean isActiveAt(Instant now) {
        return !now.isBefore(validFrom) && (validTo == null || now.isBefore(validTo));
    }
}
