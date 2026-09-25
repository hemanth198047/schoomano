package com.schoomano.domain.access;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Emergency access to one restricted record. Mirrors the DB constraints on break_glass_grant. */
public record BreakGlassGrant(String resourceType, UUID resourceId, String justification,
                              Instant grantedAt, Instant expiresAt) {

    public static final Duration MAX_DURATION = Duration.ofHours(4);
    public static final int MIN_JUSTIFICATION = 20;

    public BreakGlassGrant {
        Objects.requireNonNull(resourceType, "resourceType");
        Objects.requireNonNull(resourceId, "resourceId");
        if (justification == null || justification.strip().length() < MIN_JUSTIFICATION) {
            throw new IllegalArgumentException("break-glass justification must be at least "
                + MIN_JUSTIFICATION + " characters");
        }
        if (!expiresAt.isAfter(grantedAt) || Duration.between(grantedAt, expiresAt).compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException("break-glass must expire within " + MAX_DURATION);
        }
    }

    public boolean covers(Resource resource, Instant now) {
        return resource.type().equals(resourceType) && resource.id().equals(resourceId)
            && !now.isBefore(grantedAt) && now.isBefore(expiresAt);
    }
}
