package com.schoomano.domain.access;

import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.domain.tenancy.TenantId;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What is being accessed. {@code subjectStudentId} links the record to a child so guardian
 * relationships can apply; {@code caseTeam} lists people explicitly assigned to a restricted case.
 */
public record Resource(String type, UUID id, ScopePath scope, Sensitivity sensitivity,
                       UUID subjectStudentId, Set<UUID> caseTeam) {
    public Resource {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(sensitivity, "sensitivity");
        caseTeam = caseTeam == null ? Set.of() : Set.copyOf(caseTeam);
    }

    public static Resource ordinary(String type, UUID id, ScopePath scope, UUID subjectStudentId) {
        return new Resource(type, id, scope, Sensitivity.ORDINARY, subjectStudentId, Set.of());
    }

    public TenantId tenant() {
        return scope.tenant();
    }
}
