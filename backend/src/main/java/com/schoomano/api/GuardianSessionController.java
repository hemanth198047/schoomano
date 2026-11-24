package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.people.RelationshipRules;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The school office sees where a guardian is signed in and can end those sessions (ADR 0009: a lost or shared
 * phone, or a number that changed hands). Same authority as editing the guardian (PERSON_MANAGE for one of the
 * guardian's children); anyone else gets "not found".
 */
@RestController
@RequestMapping("/api/v1/guardians/{guardianId}/sessions")
public class GuardianSessionController {

    private final TenantTx tenantTx;
    private final ScopeDirectory scopes;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final Clock clock;

    public GuardianSessionController(TenantTx tenantTx, ScopeDirectory scopes, Authorizer authz, AuditWriter audit, Clock clock) {
        this.tenantTx = tenantTx;
        this.scopes = scopes;
        this.authz = authz;
        this.audit = audit;
        this.clock = clock;
    }

    public record SessionView(UUID id, String deviceLabel, OffsetDateTime signedInAt, OffsetDateTime lastSeenAt) { }

    public record RevokeRequest(@NotBlank String reason) { }

    @GetMapping
    public List<SessionView> list(RequestContext ctx, @PathVariable UUID guardianId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireManage(jdbc, ctx, guardianId);
            return jdbc.query("select session_id, device_label, created_at, last_seen_at from auth_sessions_for_person(?, ?, ?)",
                (rs, i) -> new SessionView(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class)),
                ctx.tenant().value(), guardianId, now());
        });
    }

    @PostMapping("/revoke")
    public Map<String, Object> revokeAll(RequestContext ctx, @PathVariable UUID guardianId, @Valid @RequestBody RevokeRequest req) {
        if (req.reason().trim().length() < RelationshipRules.MIN_REASON) {
            throw ApiException.badRequest("reason_required",
                "Give a reason of at least " + RelationshipRules.MIN_REASON + " characters. It is kept in the audit trail.");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireManage(jdbc, ctx, guardianId);
            Integer ended = jdbc.queryForObject("select auth_sessions_revoke_for_person(?, ?, ?)", Integer.class,
                ctx.tenant().value(), guardianId, now());
            int count = ended == null ? 0 : ended;
            audit.write(jdbc, ctx, "guardian.sessions_revoked", "person", guardianId.toString(), "SUCCESS",
                req.reason().trim(), Map.of("sessionsEnded", count));
            return Map.of("revoked", count);
        });
    }

    private void requireManage(JdbcTemplate jdbc, RequestContext ctx, UUID guardianId) {
        List<UUID> students = jdbc.queryForList(
            "select student_person_id from guardian_relationship where guardian_person_id = ? and status <> 'ENDED'",
            UUID.class, guardianId);
        for (UUID student : students) {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), student).orElse(ScopePath.group(ctx.tenant()));
            if (authz.policy().decide(ctx.actor(), Permission.PERSON_MANAGE,
                Resource.ordinary("guardian", guardianId, path, null), clock.instant()).allowed()) {
                return;
            }
        }
        throw ApiException.notFound();
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
