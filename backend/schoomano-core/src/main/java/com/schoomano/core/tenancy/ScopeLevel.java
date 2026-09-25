package com.schoomano.core.tenancy;

/** Tenant hierarchy below the platform, ordered from broadest to narrowest. */
public enum ScopeLevel {
    GROUP,
    LEGAL_ENTITY,
    CAMPUS,
    PROGRAMME,
    ACADEMIC_YEAR,
    CLASS;

    public boolean isBroaderThan(ScopeLevel other) {
        return ordinal() < other.ordinal();
    }
}
