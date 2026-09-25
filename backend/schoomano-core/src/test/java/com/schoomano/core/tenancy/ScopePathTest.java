package com.schoomano.core.tenancy;

import static com.schoomano.core.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopePathTest {

    @Test
    void pathMustStartAtTheTenantGroup() {
        assertThrows(IllegalArgumentException.class,
            () -> new ScopePath(TENANT_A, List.of(NORTH)));
        assertThrows(IllegalArgumentException.class,
            () -> new ScopePath(TENANT_A, List.of(new ScopeNode(ScopeLevel.GROUP, TENANT_B.value()))));
    }

    @Test
    void levelsMustNarrowStrictly() {
        var year = new ScopeNode(ScopeLevel.ACADEMIC_YEAR, UUID.randomUUID());
        var path = northPath().child(year);
        assertThrows(IllegalArgumentException.class, () -> path.child(SOUTH));
    }

    @Test
    void recordIsWithinEveryAncestorButNotASibling() {
        var path = northPath();
        assertTrue(path.isWithin(GROUP_A));
        assertTrue(path.isWithin(NORTH));
        assertFalse(path.isWithin(SOUTH));
    }
}
