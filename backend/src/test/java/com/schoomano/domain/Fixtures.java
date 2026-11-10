package com.schoomano.domain;

import com.schoomano.domain.tenancy.ScopeLevel;
import com.schoomano.domain.tenancy.ScopeNode;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.domain.tenancy.TenantId;
import java.time.Instant;
import java.util.UUID;

/** Synthetic identifiers aligned with backend/db/seed/10_base.sql. */
public final class Fixtures {
    public static final TenantId TENANT_A = TenantId.of("10000000-0000-4000-8000-000000000001");
    public static final TenantId TENANT_B = TenantId.of("20000000-0000-4000-8000-000000000001");
    public static final ScopeNode GROUP_A = new ScopeNode(ScopeLevel.GROUP, TENANT_A.value());
    public static final ScopeNode NORTH = ScopeNode.of(ScopeLevel.CAMPUS, "12000000-0000-4000-8000-000000000001");
    public static final ScopeNode SOUTH = ScopeNode.of(ScopeLevel.CAMPUS, "12000000-0000-4000-8000-000000000002");
    public static final ScopeNode OTHER_CAMPUS = ScopeNode.of(ScopeLevel.CAMPUS, "22000000-0000-4000-8000-000000000001");

    public static final UUID ASHA_ADMIN = UUID.fromString("13000000-0000-4000-8000-000000000001");
    public static final UUID RAVI_TEACHER = UUID.fromString("13000000-0000-4000-8000-000000000002");
    public static final UUID FARAH_LEAD = UUID.fromString("13000000-0000-4000-8000-000000000003");
    public static final UUID MEERA_GUARDIAN = UUID.fromString("13000000-0000-4000-8000-000000000010");
    public static final UUID KARAN_GUARDIAN = UUID.fromString("13000000-0000-4000-8000-000000000011");
    public static final UUID ANAYA = UUID.fromString("13000000-0000-4000-8000-000000000020");
    public static final UUID VIVAAN = UUID.fromString("13000000-0000-4000-8000-000000000021");
    public static final UUID OTHER_TENANT_STUDENT = UUID.fromString("23000000-0000-4000-8000-000000000001");

    public static final Instant YEAR_START = Instant.parse("2026-04-01T00:00:00Z");
    public static final Instant NOW = Instant.parse("2026-09-25T04:30:00Z");

    public static ScopePath northPath() {
        return ScopePath.group(TENANT_A).child(NORTH);
    }

    public static ScopePath southPath() {
        return ScopePath.group(TENANT_A).child(SOUTH);
    }

    public static ScopePath otherTenantPath() {
        return ScopePath.group(TENANT_B).child(OTHER_CAMPUS);
    }

    private Fixtures() {
    }
}
