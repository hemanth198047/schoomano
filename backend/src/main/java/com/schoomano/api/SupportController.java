package com.schoomano.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * B10 support console (M30). The school approves platform support for a named person, a stated purpose and a short
 * time, can end it at any moment, and can read everything support did in the audit trail. Support itself can read
 * the audit trail and the operational health of the school and nothing else.
 */
@RestController
@RequestMapping("/api/v1")
public class SupportController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final ObjectMapper json;

    public SupportController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, ObjectMapper json) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.json = json;
    }

    public record GrantRequest(@NotBlank @Size(min = 3, max = 200) String supportUserRef,
                               @NotBlank @Size(min = 10, max = 500) String purpose, @Min(1) @Max(8) int hours) { }

    public record GrantView(UUID id, String supportUserRef, String purpose, String approvedBy, OffsetDateTime startsAt,
                            OffsetDateTime expiresAt, OffsetDateTime revokedAt, String state) { }

    public record RevokeRequest(@Size(max = 200) String reason) { }

    public record AuditRow(long seq, OffsetDateTime occurredAt, String actorKind, String actorName, String action, String resourceType,
                           String resourceId, String outcome, String reason, String correlationId, JsonNode details) { }

    public record Verification(long checked, boolean intact, Long brokenSeq, String problem) { }

    public record Check(String key, String label, String level, String detail) { }

    public record Health(OffsetDateTime at, List<Check> checks, int supportAccessLive) { }

    private static ScopePath group(RequestContext ctx) {
        return ScopePath.group(ctx.tenant());
    }

    private void require(JdbcTemplate jdbc, RequestContext ctx, Permission p, String type) {
        authz.requireOrNotFound(jdbc, ctx, p, Resource.ordinary(type, null, group(ctx), null));
    }

    // ---- support grants (the school's side) -----------------------------------------------------------------

    private static final String GRANT_SQL = """
        select g.id, g.support_user_ref, g.purpose, trim(p.given_name || ' ' || coalesce(p.family_name, '')), g.starts_at, g.expires_at, g.revoked_at,
               case when g.revoked_at is not null then 'REVOKED' when g.expires_at <= now() then 'EXPIRED'
                    when g.starts_at > now() then 'UPCOMING' else 'ACTIVE' end
          from support_access_grant g join person p on p.id = g.approved_by
        """;

    private static GrantView grant(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new GrantView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class), rs.getString(8));
    }

    @GetMapping("/support/grants")
    public List<GrantView> grants(RequestContext ctx) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.SUPPORT_GRANT_MANAGE, "support_grant");
            return jdbc.query(GRANT_SQL + " order by g.created_at desc limit 100", SupportController::grant);
        });
    }

    @PostMapping("/support/grants")
    @ResponseStatus(HttpStatus.CREATED)
    public GrantView approve(RequestContext ctx, @Valid @RequestBody GrantRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.SUPPORT_GRANT_MANAGE, "support_grant");
            // The database's clock decides when access starts and ends, the same clock that later checks it.
            UUID id = jdbc.queryForObject("""
                insert into support_access_grant (tenant_id, support_user_ref, approved_by, purpose, starts_at, expires_at)
                values (?, ?, ?, ?, now(), now() + make_interval(hours => ?)) returning id
                """, UUID.class, ctx.tenant().value(), req.supportUserRef().trim(), ctx.actor().personId(), req.purpose().trim(), req.hours());
            audit.write(jdbc, ctx, "support.grant_approved", "support_grant", id.toString(), "SUCCESS", req.purpose().trim(),
                Map.of("supportUserRef", req.supportUserRef().trim(), "hours", req.hours()));
            return jdbc.queryForObject(GRANT_SQL + " where g.id = ?", SupportController::grant, id);
        });
    }

    @PostMapping("/support/grants/{id}/revoke")
    public GrantView revoke(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody(required = false) RevokeRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.SUPPORT_GRANT_MANAGE, "support_grant");
            GrantView current = jdbc.query(GRANT_SQL + " where g.id = ?", SupportController::grant, id).stream().findFirst()
                .orElseThrow(ApiException::notFound);
            if (!current.state().equals("ACTIVE") && !current.state().equals("UPCOMING")) {
                throw new ApiException(HttpStatus.CONFLICT, "grant_not_open", "That access has already ended.");
            }
            jdbc.update("update support_access_grant set revoked_at = now(), revoked_by = ? where id = ?", ctx.actor().personId(), id);
            audit.write(jdbc, ctx, "support.grant_revoked", "support_grant", id.toString(), "SUCCESS",
                req == null || req.reason() == null ? null : req.reason().trim(), Map.of("supportUserRef", current.supportUserRef()));
            return jdbc.queryForObject(GRANT_SQL + " where g.id = ?", SupportController::grant, id);
        });
    }

    // ---- audit trail ----------------------------------------------------------------------------------------

    @GetMapping("/audit")
    public List<AuditRow> auditTrail(RequestContext ctx, @RequestParam(required = false) String actorKind,
                                     @RequestParam(required = false) String action, @RequestParam(required = false) Long beforeSeq,
                                     @RequestParam(defaultValue = "50") int limit) {
        int n = Math.max(1, Math.min(limit, 200));
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.AUDIT_READ, "audit");
            return jdbc.query("""
                select a.seq, a.occurred_at, a.actor_kind,
                       case when a.actor_kind = 'SUPPORT' then 'Support: ' || coalesce(g.support_user_ref, 'unknown')
                            when p.id is not null then trim(p.given_name || ' ' || coalesce(p.family_name, ''))
                            else a.actor_kind end,
                       a.action, a.resource_type, a.resource_id, a.outcome, a.reason, a.correlation_id, a.details::text
                  from audit_event a
                  left join person p on p.id = a.actor_person_id and a.actor_kind <> 'SUPPORT'
                  left join support_access_grant g on g.id = a.actor_person_id and a.actor_kind = 'SUPPORT'
                 where (?::text is null or a.actor_kind = ?::text)
                   and (?::text is null or a.action like ?::text || '%')
                   and (?::bigint is null or a.seq < ?::bigint)
                 order by a.seq desc limit ?
                """, (rs, i) -> new AuditRow(rs.getLong(1), rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), parse(rs.getString(11))),
                actorKind, actorKind, action, action, beforeSeq, beforeSeq, n);
        });
    }

    @GetMapping("/audit/verify")
    public Verification verify(RequestContext ctx) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.AUDIT_READ, "audit");
            return jdbc.queryForObject("select checked, broken_seq, problem from audit_verify(?)",
                (rs, i) -> new Verification(rs.getLong(1), rs.getObject(2) == null, (Long) rs.getObject(2), rs.getString(3)), ctx.tenant().value());
        });
    }

    private JsonNode parse(String raw) {
        try {
            return json.readTree(raw == null ? "{}" : raw);
        } catch (JsonProcessingException e) {
            return json.createObjectNode();
        }
    }

    // ---- health ---------------------------------------------------------------------------------------------

    /** What an operator needs at a glance. Each check says plainly whether something needs a person. */
    @GetMapping("/operations/health")
    public Health health(RequestContext ctx) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.AUDIT_READ, "audit");
            List<Check> checks = new ArrayList<>();

            long backlog = count(jdbc, "select count(*) from outbox_event where published_at is null");
            long oldest = count(jdbc, "select coalesce(extract(epoch from now() - min(occurred_at))::bigint, 0) from outbox_event where published_at is null");
            checks.add(new Check("outbox", "Events waiting to be delivered", backlog == 0 ? "OK" : oldest > 600 ? "ATTENTION" : "OK",
                backlog == 0 ? "None waiting." : backlog + " waiting, the oldest for " + (oldest / 60) + " minutes."));

            long smsFailed = count(jdbc, "select count(*) from notice_delivery where channel = 'SMS' and status = 'FAILED' and created_at > now() - interval '7 days'");
            long smsWaiting = count(jdbc, "select count(*) from notice_delivery where channel = 'SMS' and status = 'PENDING' and send_after <= now()");
            checks.add(new Check("sms", "Text messages", smsFailed > 0 ? "ATTENTION" : "OK",
                smsFailed + " failed in the last 7 days, " + smsWaiting + " due to go out."));

            long stale = count(jdbc, "select count(*) from payment where status = 'PENDING' and created_at < now() - interval '1 day'");
            long refunds = count(jdbc, "select count(*) from payment where status = 'NEEDS_REFUND'");
            checks.add(new Check("payments", "Online payments", stale + refunds > 0 ? "ATTENTION" : "OK",
                refunds + " need a refund, " + stale + " started over a day ago and never confirmed."));

            long conflicts = count(jdbc, "select count(*) from attendance_conflict where state = 'OPEN'");
            checks.add(new Check("attendance", "Attendance conflicts", conflicts > 0 ? "ATTENTION" : "OK",
                conflicts == 0 ? "None waiting for the office." : conflicts + " waiting for the office to settle."));

            long denied = count(jdbc, "select count(*) from audit_event where outcome = 'DENY' and occurred_at > now() - interval '1 day'");
            long events = count(jdbc, "select count(*) from audit_event where occurred_at > now() - interval '1 day'");
            checks.add(new Check("audit", "Audit trail, last 24 hours", "OK", events + " events, " + denied + " refused."));

            int live = (int) count(jdbc, "select count(*) from support_access_grant where revoked_at is null and starts_at <= now() and expires_at > now()");
            checks.add(new Check("support", "Platform support access", "OK",
                live == 0 ? "No one from support has access now." : live + " approved access open now."));
            return new Health(OffsetDateTime.now(), checks, live);
        });
    }

    private static long count(JdbcTemplate jdbc, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
