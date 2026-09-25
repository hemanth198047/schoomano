package com.schoomano.core.tenancy;

import java.util.Objects;
import java.util.UUID;

/** Tenant = school group. Always derived from the authenticated principal, never from client input. */
public record TenantId(UUID value) {
    public TenantId {
        Objects.requireNonNull(value, "tenant id");
    }

    public static TenantId of(String uuid) {
        return new TenantId(UUID.fromString(uuid));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
