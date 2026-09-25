package com.schoomano.domain.access;

import com.schoomano.domain.tenancy.ScopeNode;
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
