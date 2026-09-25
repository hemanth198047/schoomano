package com.schoomano.core.access;

import com.schoomano.core.tenancy.TenantId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The authenticated principal as loaded server-side. A single person can hold staff grants and
 * guardian links at the same time (a teacher who is also a parent).
 */
public record Actor(UUID personId, TenantId tenant, Kind kind, List<RoleGrant> grants,
                    List<GuardianLink> guardianLinks, List<BreakGlassGrant> breakGlass,
                    Optional<SupportSession> support) {

    public enum Kind { USER, SUPPORT }

    public Actor {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(kind, "kind");
        grants = List.copyOf(grants);
        guardianLinks = List.copyOf(guardianLinks);
        breakGlass = List.copyOf(breakGlass);
        support = support == null ? Optional.empty() : support;
        if (kind == Kind.SUPPORT && support.isEmpty()) {
            throw new IllegalArgumentException("support actor requires a support session");
        }
    }

    public static Actor user(UUID personId, TenantId tenant, List<RoleGrant> grants, List<GuardianLink> links) {
        return new Actor(personId, tenant, Kind.USER, grants, links, List.of(), Optional.empty());
    }

    public Actor withBreakGlass(BreakGlassGrant grant) {
        var all = new java.util.ArrayList<>(breakGlass);
        all.add(grant);
        return new Actor(personId, tenant, kind, grants, guardianLinks, all, support);
    }
}
