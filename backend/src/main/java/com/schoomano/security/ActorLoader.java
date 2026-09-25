package com.schoomano.security;

import com.schoomano.domain.access.Actor;
import com.schoomano.domain.access.BreakGlassGrant;
import com.schoomano.domain.access.GuardianCapability;
import com.schoomano.domain.access.GuardianLink;
import com.schoomano.domain.access.RoleGrant;
import com.schoomano.domain.tenancy.ScopeLevel;
import com.schoomano.domain.tenancy.ScopeNode;
import com.schoomano.domain.tenancy.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Loads grants, guardian links and live break-glass grants for a person. Call inside TenantTx. */
@Component
public class ActorLoader {

    public Actor load(JdbcTemplate jdbc, TenantId tenant, UUID personId) {
        List<RoleGrant> grants = jdbc.query("""
            select role_key, scope_level, scope_id, valid_from, valid_to from role_grant
             where person_id = ? and (valid_to is null or valid_to > now())
            """, (rs, i) -> new RoleGrant(rs.getString(1),
                new ScopeNode(ScopeLevel.valueOf(rs.getString(2)), rs.getObject(3, UUID.class)),
                instant(rs, 4), instant(rs, 5)), personId);

        List<GuardianLink> links = jdbc.query("""
            select student_person_id, status, can_view_attendance, can_view_academic, can_pay_fees,
                   can_give_consent, can_receive_notices, can_pickup, valid_from, valid_to
              from guardian_relationship
             where guardian_person_id = ? and status in ('ACTIVE','RESTRICTED')
            """, (rs, i) -> new GuardianLink(rs.getObject(1, UUID.class),
                GuardianLink.Status.valueOf(rs.getString(2)), capabilities(rs), instant(rs, 9), instant(rs, 10)),
            personId);

        List<BreakGlassGrant> breakGlass = jdbc.query("""
            select resource_type, resource_id, justification, granted_at, expires_at from break_glass_grant
             where person_id = ? and expires_at > now()
            """, (rs, i) -> new BreakGlassGrant(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3),
                instant(rs, 4), instant(rs, 5)), personId);

        return new Actor(personId, tenant, Actor.Kind.USER, grants, links, breakGlass, Optional.empty());
    }

    private static EnumSet<GuardianCapability> capabilities(ResultSet rs) throws SQLException {
        EnumSet<GuardianCapability> caps = EnumSet.noneOf(GuardianCapability.class);
        if (rs.getBoolean(3)) caps.add(GuardianCapability.VIEW_ATTENDANCE);
        if (rs.getBoolean(4)) caps.add(GuardianCapability.VIEW_ACADEMIC);
        if (rs.getBoolean(5)) caps.add(GuardianCapability.PAY_FEES);
        if (rs.getBoolean(6)) caps.add(GuardianCapability.GIVE_CONSENT);
        if (rs.getBoolean(7)) caps.add(GuardianCapability.RECEIVE_NOTICES);
        if (rs.getBoolean(8)) caps.add(GuardianCapability.PICKUP);
        return caps;
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }
}
