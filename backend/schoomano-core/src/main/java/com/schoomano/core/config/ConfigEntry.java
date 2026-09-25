package com.schoomano.core.config;

import com.schoomano.core.tenancy.ScopeNode;
import java.time.Instant;
import java.util.Objects;

/** One version of a configuration value at one scope. Mirrors the config_version table. */
public record ConfigEntry(String key, ScopeNode scope, int version, String valueJson,
                          Instant effectiveFrom, Instant effectiveTo, Status status) {

    public enum Status { DRAFT, PUBLISHED, RETIRED }

    public ConfigEntry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(valueJson, "valueJson");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        Objects.requireNonNull(status, "status");
        if (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveTo must be after effectiveFrom");
        }
    }

    public boolean coversInstant(Instant at) {
        return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
    }
}
