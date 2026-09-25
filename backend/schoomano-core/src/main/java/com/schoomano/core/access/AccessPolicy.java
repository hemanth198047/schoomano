package com.schoomano.core.access;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side authorization: tenant, then restricted partitions, then role scope, then guardian
 * relationship. Deny by default. Pure function of its inputs so it can be tested exhaustively.
 */
public final class AccessPolicy {

    public static final String POLICY_VERSION = "access-2026.09.1";

    /** Support staff may only read operational metadata, never child records beyond basics. */
    private static final Set<Permission> SUPPORT_PERMISSIONS =
        EnumSet.of(Permission.CAMPUS_READ, Permission.CONFIG_READ, Permission.AUDIT_READ, Permission.STUDENT_READ_BASIC);

    private final RoleCatalog roles;

    public AccessPolicy(RoleCatalog roles) {
        this.roles = Objects.requireNonNull(roles);
    }

    public AccessDecision decide(Actor actor, Permission permission, Resource resource, Instant now) {
        if (!actor.tenant().equals(resource.tenant())) {
            return AccessDecision.deny("tenant mismatch", true);
        }

        if (actor.kind() == Actor.Kind.SUPPORT) {
            var session = actor.support().orElseThrow();
            if (!session.tenant().equals(resource.tenant()) || !session.isActiveAt(now)) {
                return AccessDecision.deny("no active support grant for tenant", true);
            }
            if (resource.sensitivity() != Sensitivity.ORDINARY || !SUPPORT_PERMISSIONS.contains(permission)) {
                return AccessDecision.deny("support access is limited to operational metadata", true);
            }
            return AccessDecision.allow(AccessDecision.Basis.SUPPORT, "time-limited support grant", true);
        }

        if (resource.sensitivity() != Sensitivity.ORDINARY) {
            return decideRestricted(actor, permission, resource, now);
        }

        if (hasScopedPermission(actor, permission, resource, now)) {
            return AccessDecision.allow(AccessDecision.Basis.ROLE, "role grant in scope", permission.alwaysAudited());
        }

        if (resource.subjectStudentId() != null && guardianAllows(actor, permission, resource.subjectStudentId(), now)) {
            return AccessDecision.allow(AccessDecision.Basis.RELATIONSHIP, "guardian relationship",
                permission.alwaysAudited());
        }

        return AccessDecision.deny("no grant or relationship", permission.alwaysAudited());
    }

    private AccessDecision decideRestricted(Actor actor, Permission permission, Resource resource, Instant now) {
        Permission required = resource.sensitivity().requiredPermission();
        if (permission != required) {
            return AccessDecision.deny("restricted record requires " + required, true);
        }
        if (actor.personId() != null && resource.caseTeam().contains(actor.personId())) {
            return AccessDecision.allow(AccessDecision.Basis.CASE_TEAM, "assigned to case", true);
        }
        if (hasScopedPermission(actor, required, resource, now)) {
            return AccessDecision.allow(AccessDecision.Basis.ROLE, "designated lead in scope", true);
        }
        for (BreakGlassGrant grant : actor.breakGlass()) {
            if (grant.covers(resource, now)) {
                return AccessDecision.allow(AccessDecision.Basis.BREAK_GLASS,
                    "break-glass: review required", true);
            }
        }
        return AccessDecision.deny("restricted record", true);
    }

    private boolean hasScopedPermission(Actor actor, Permission permission, Resource resource, Instant now) {
        for (RoleGrant grant : actor.grants()) {
            if (grant.isActiveAt(now)
                && resource.scope().isWithin(grant.scope())
                && roles.permissionsOf(grant.roleKey()).contains(permission)) {
                return true;
            }
        }
        return false;
    }

    private boolean guardianAllows(Actor actor, Permission permission, UUID studentId, Instant now) {
        List<GuardianLink> links = actor.guardianLinks().stream()
            .filter(l -> l.studentId().equals(studentId)).toList();
        if (permission == Permission.STUDENT_READ_BASIC) {
            return links.stream().anyMatch(l -> l.isUsableAt(now));
        }
        return GuardianCapability.forPermission(permission)
            .map(cap -> links.stream().anyMatch(l -> l.allows(cap, now)))
            .orElse(false);
    }

    /** Children a guardian may switch to for a given capability (drives the mobile child switcher). */
    public List<UUID> studentsFor(Actor actor, GuardianCapability capability, Instant now) {
        return actor.guardianLinks().stream()
            .filter(l -> l.allows(capability, now))
            .map(GuardianLink::studentId)
            .distinct()
            .toList();
    }
}
