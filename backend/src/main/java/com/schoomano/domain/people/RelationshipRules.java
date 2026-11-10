package com.schoomano.domain.people;

import com.schoomano.domain.access.GuardianCapability;
import com.schoomano.domain.access.GuardianLink;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Rules for changing a guardian's relationship to a child. Every change needs a reason (kept in the audit
 * trail; court-order details belong in the restricted document vault, never here). An ended relationship is
 * history and cannot be reopened: a new relationship is created instead. A RESTRICTED relationship (for
 * example a separated parent) may not keep pickup unless that is explicitly confirmed.
 */
public final class RelationshipRules {

    private RelationshipRules() {
    }

    public static final int MIN_REASON = 10;

    /** What a newly restricted relationship keeps unless staff choose otherwise: notices only. */
    public static final Set<GuardianCapability> RESTRICTED_DEFAULT = EnumSet.of(GuardianCapability.RECEIVE_NOTICES);

    public record State(GuardianLink.Status status, Set<GuardianCapability> capabilities) { }

    public static List<String> problems(State before, State after, String reason, boolean pickupConfirmed) {
        List<String> out = new ArrayList<>();
        if (reason == null || reason.trim().length() < MIN_REASON) {
            out.add("Give a reason of at least " + MIN_REASON + " characters. It is kept in the audit trail.");
        }
        if (before != null && before.status() == GuardianLink.Status.ENDED && after.status() != GuardianLink.Status.ENDED) {
            out.add("An ended relationship cannot be reopened. Add the guardian again to start a new one.");
        }
        if (after.status() == GuardianLink.Status.RESTRICTED && after.capabilities().contains(GuardianCapability.PICKUP)
            && !pickupConfirmed) {
            out.add("A restricted guardian normally cannot collect the child. Confirm pickup explicitly if a court "
                + "order or the school's decision allows it.");
        }
        if (before != null && before.equals(after)) {
            out.add("Nothing changed.");
        }
        return List.copyOf(out);
    }

    /** Old-system-owned fields that cannot be edited in Schoomano while coexistence is on. */
    public static List<String> lockedEdits(Set<String> requestedFields, Set<String> ownedFields) {
        return requestedFields.stream().filter(ownedFields::contains).sorted().toList();
    }
}
