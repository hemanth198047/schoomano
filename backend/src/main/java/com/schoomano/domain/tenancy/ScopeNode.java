package com.schoomano.domain.tenancy;

import java.util.Objects;
import java.util.UUID;

public record ScopeNode(ScopeLevel level, UUID id) {
    public ScopeNode {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(id, "id");
    }

    public static ScopeNode of(ScopeLevel level, String uuid) {
        return new ScopeNode(level, UUID.fromString(uuid));
    }
}
