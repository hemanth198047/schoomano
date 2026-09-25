package com.schoomano.domain.access;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One guardian-to-student relationship with explicit capabilities (custody-aware). */
public record GuardianLink(UUID studentId, Status status, Set<GuardianCapability> capabilities,
                           Instant validFrom, Instant validTo) {

    public enum Status { ACTIVE, RESTRICTED, SUSPENDED, ENDED }

    public GuardianLink {
        Objects.requireNonNull(studentId, "studentId");
        Objects.requireNonNull(status, "status");
        capabilities = Set.copyOf(capabilities);
        Objects.requireNonNull(validFrom, "validFrom");
    }

    /** RESTRICTED links still work, but only for the capabilities explicitly left on them. */
    public boolean isUsableAt(Instant now) {
        boolean inWindow = !now.isBefore(validFrom) && (validTo == null || now.isBefore(validTo));
        return inWindow && (status == Status.ACTIVE || status == Status.RESTRICTED);
    }

    public boolean allows(GuardianCapability capability, Instant now) {
        return isUsableAt(now) && capabilities.contains(capability);
    }
}
