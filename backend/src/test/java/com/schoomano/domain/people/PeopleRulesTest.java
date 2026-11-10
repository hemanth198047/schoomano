package com.schoomano.domain.people;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.schoomano.domain.access.GuardianCapability;
import com.schoomano.domain.access.GuardianLink.Status;
import com.schoomano.domain.people.RelationshipRules.State;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PeopleRulesTest {

    @Test
    void mobileNumbersAreNormalisedFromTheWaysParentsWriteThem() {
        for (String in : List.of("98450 12345", "+91-98450-12345", "098450 12345", "919845012345", "(+91) 98450.12345")) {
            assertEquals(Optional.of("+919845012345"), IndianMobile.normalise(in), in);
        }
        assertEquals("+91 98450 12345", IndianMobile.display("+919845012345"));
    }

    @Test
    void landlinesForeignAndShortNumbersAreRejected() {
        for (String in : List.of("0821 234567", "+1 415 555 0100", "98450", "58450 12345", "", "abc")) {
            assertTrue(IndianMobile.normalise(in).isEmpty(), in);
        }
    }

    final State active = new State(Status.ACTIVE, EnumSet.allOf(GuardianCapability.class));

    @Test
    void everyChangeNeedsAReason() {
        var after = new State(Status.RESTRICTED, RelationshipRules.RESTRICTED_DEFAULT);
        assertEquals(1, RelationshipRules.problems(active, after, "court", false).size());
        assertTrue(RelationshipRules.problems(active, after, "Separation agreement on file", false).isEmpty());
    }

    @Test
    void restrictedGuardianKeepsPickupOnlyWhenConfirmed() {
        var after = new State(Status.RESTRICTED, EnumSet.of(GuardianCapability.RECEIVE_NOTICES, GuardianCapability.PICKUP));
        assertEquals(1, RelationshipRules.problems(active, after, "Weekend pickup allowed by order", false).size());
        assertTrue(RelationshipRules.problems(active, after, "Weekend pickup allowed by order", true).isEmpty());
    }

    @Test
    void endedRelationshipsAreNotReopenedAndNoOpsAreRefused() {
        var ended = new State(Status.ENDED, Set.of());
        assertTrue(RelationshipRules.problems(ended, active, "Parent asked to be added back", false).get(0)
            .startsWith("An ended relationship"));
        assertEquals(List.of("Nothing changed."), RelationshipRules.problems(active, active, "No real change here", false));
    }

    @Test
    void ownedFieldsAreLocked() {
        assertEquals(List.of("familyName"), RelationshipRules.lockedEdits(Set.of("familyName", "preferredName"),
            Set.of("givenName", "familyName", "dateOfBirth", "placement")));
    }
}
