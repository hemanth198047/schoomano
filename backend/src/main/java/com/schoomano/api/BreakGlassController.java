package com.schoomano.api;

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
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M30 break-glass. A member of staff who must see a restricted health or safeguarding record in an emergency states why and
 * gets access for up to four hours (the access policy already honours the grant). Every grant is then reviewed by someone with
 * authority over the audit trail, never by the person it was made to. The review screen shows how often the record was opened.
 */
@RestController
@RequestMapping("/api/v1/break-glass")
public class BreakGlassController {

    private static final int MAX_LIVE_PER_PERSON = 3;

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;

    public BreakGlassController(TenantTx tenantTx, Authorizer authz, AuditWriter audit) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
    }

    public record Request(@NotBlank @Pattern(regexp = "health_record|safeguarding_case") String resourceType, @NotNull UUID resourceId,
                          @NotBlank @Size(min = 20, max = 500) String justification, @Min(5) @Max(240) int minutes) { }

    public record Review(@NotBlank @Pattern(regexp = "APPROPRIATE|INAPPROPRIATE|ESCALATED") String outcome,
                         @NotBlank @Size(min = 10, max = 500) String note) { }

    public record GrantView(UUID id, String person, UUID personId, String resourceType, UUID resourceId, String justification,
                            OffsetDateTime grantedAt, OffsetDateTime expiresAt, String state, int opened, String reviewedBy,
                            OffsetDateTime reviewedAt, String outcome, String note) { }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GrantView request(RequestContext ctx, @Valid @RequestBody Request req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            if (ctx.actor().grants().isEmpty() || ctx.actor().personId() == null) {
                throw ApiException.notFound();
            }
            boolean exists = "health_record".equals(req.resourceType())
                ? Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from student_enrollment where student_person_id = ?)", Boolean.class, req.resourceId()))
                : Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from safeguarding_report where id = ?)", Boolean.class, req.resourceId()));
            if (!exists) {
                throw ApiException.notFound();
            }
            long live = jdbc.queryForObject("select count(*) from break_glass_grant where person_id = ? and expires_at > now()", Long.class, ctx.actor().personId());
            if (live >= MAX_LIVE_PER_PERSON) {
                throw new ApiException(HttpStatus.CONFLICT, "too_many_live", "You already have " + MAX_LIVE_PER_PERSON + " emergency accesses running. Wait for one to end.");
            }
            UUID id = jdbc.queryForObject("""
                insert into break_glass_grant (tenant_id, person_id, resource_type, resource_id, justification, expires_at)
                values (?, ?, ?, ?, ?, now() + make_interval(mins => ?)) returning id
                """, UUID.class, ctx.tenant().value(), ctx.actor().personId(), req.resourceType(), req.resourceId(), req.justification().trim(), req.minutes());
            audit.write(jdbc, ctx, "breakglass.granted", "break_glass_grant", id.toString(), "SUCCESS", null,
                Map.of("resourceType", req.resourceType(), "minutes", req.minutes()));
            UUID campus = campusOf(jdbc, req.resourceType(), req.resourceId());
            String who = jdbc.queryForObject("select trim(given_name || ' ' || coalesce(family_name, '')) from person where id = ?", String.class, ctx.actor().personId());
            tell(jdbc, campus, "Emergency access used", who + " opened a " + kindText(req.resourceType()) + " in an emergency for up to "
                + req.minutes() + " minutes. Please review it under Support and audit.", reviewers(jdbc, campus, ctx.actor().personId()));
            return grants(jdbc, "g.id = ?", id).get(0);
        });
    }

    /** Everything, unreviewed first and newest first within each group. For those who may read the audit trail. */
    @GetMapping
    public List<GrantView> list(RequestContext ctx) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireReviewer(jdbc, ctx);
            return grants(jdbc, "true");
        });
    }

    @PostMapping("/{id}/review")
    public GrantView review(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody Review req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireReviewer(jdbc, ctx);
            List<GrantView> found = grants(jdbc, "g.id = ?", id);
            if (found.isEmpty()) {
                throw ApiException.notFound();
            }
            GrantView g = found.get(0);
            if (g.reviewedAt() != null) {
                throw new ApiException(HttpStatus.CONFLICT, "already_reviewed", "This emergency access has already been reviewed.");
            }
            if (g.personId().equals(ctx.actor().personId())) {
                throw new ApiException(HttpStatus.CONFLICT, "own_grant", "Someone else must review an emergency access you used.");
            }
            try {
                jdbc.update("update break_glass_grant set reviewed_by = ?, reviewed_at = now(), review_outcome = ?, review_note = ? where id = ? and reviewed_at is null",
                    ctx.actor().personId(), req.outcome(), req.note().trim(), id);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "not_reviewable", "This emergency access cannot be reviewed.");
            }
            audit.write(jdbc, ctx, "breakglass.reviewed", "break_glass_grant", id.toString(), "SUCCESS", null, Map.of("outcome", req.outcome()));
            if (!"APPROPRIATE".equals(req.outcome())) {
                UUID campus = campusOf(jdbc, g.resourceType(), g.resourceId());
                tell(jdbc, campus, "Emergency access ended", "Your emergency access to a " + kindText(g.resourceType())
                    + " was reviewed as " + ("ESCALATED".equals(req.outcome()) ? "needing escalation" : "not appropriate") + " and has ended. Speak to the principal.",
                    List.of(g.personId()));
            }
            return grants(jdbc, "g.id = ?", id).get(0);
        });
    }

    private static String kindText(String resourceType) {
        return "health_record".equals(resourceType) ? "health record" : "safeguarding report";
    }

    /** The campus the child is enrolled at, which is where reviewers are told. */
    private static UUID campusOf(JdbcTemplate jdbc, String resourceType, UUID resourceId) {
        String sql = "health_record".equals(resourceType)
            ? "select campus_id from student_enrollment where student_person_id = ? order by created_at desc limit 1"
            : "select e.campus_id from safeguarding_report r join student_enrollment e on e.student_person_id = r.student_person_id where r.id = ? order by e.created_at desc limit 1";
        return jdbc.queryForObject(sql, UUID.class, resourceId);
    }

    /** Principals and group administrators, school-wide or of this campus, except the person who used the access. */
    private static List<UUID> reviewers(JdbcTemplate jdbc, UUID campus, UUID except) {
        return jdbc.queryForList("""
            select distinct person_id from role_grant
             where role_key in ('principal', 'group_admin') and person_id <> ? and (valid_to is null or valid_to > now())
               and (scope_level = 'GROUP' or (scope_level = 'CAMPUS' and scope_id = ?))
            """, UUID.class, except, campus);
    }

    private static void tell(JdbcTemplate jdbc, UUID campus, String title, String body, List<UUID> people) {
        if (people.isEmpty()) {
            return;
        }
        String literal = "{" + String.join(",", people.stream().map(UUID::toString).toList()) + "}";
        jdbc.queryForObject("select notice_tell_staff('SECURITY', ?, ?, ?, ?::uuid[], null)", Integer.class, campus, title, body, literal);
    }

    private void requireReviewer(JdbcTemplate jdbc, RequestContext ctx) {
        if (ctx.actor().kind() == com.schoomano.domain.access.Actor.Kind.SUPPORT) {
            throw ApiException.notFound();   // reasons for emergency access can name a child; platform support never reads them
        }
        authz.requireOrNotFound(jdbc, ctx, Permission.AUDIT_READ, Resource.ordinary("break_glass", null, ScopePath.group(ctx.tenant()), null));
    }

    private List<GrantView> grants(JdbcTemplate jdbc, String where, Object... args) {
        return jdbc.query("""
            select g.id, trim(p.given_name || ' ' || coalesce(p.family_name, '')), g.person_id, g.resource_type, g.resource_id, g.justification,
                   g.granted_at, g.expires_at,
                   case when g.reviewed_at is not null and g.review_outcome <> 'APPROPRIATE' then 'ENDED'
                        when g.expires_at > now() then 'ACTIVE' else 'EXPIRED' end,
                   (select count(*) from audit_event a where a.actor_person_id = g.person_id and a.resource_id = g.resource_id::text
                       and a.outcome = 'ALLOW' and a.occurred_at >= g.granted_at and a.occurred_at <= g.expires_at)::int,
                   trim(r.given_name || ' ' || coalesce(r.family_name, '')), g.reviewed_at, g.review_outcome, g.review_note
              from break_glass_grant g join person p on p.id = g.person_id left join person r on r.id = g.reviewed_by
             where """ + " " + where + " order by (g.reviewed_at is null) desc, g.granted_at desc limit 100",
            (rs, i) -> new GrantView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getObject(5, UUID.class),
                rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), rs.getString(9), rs.getInt(10),
                rs.getString(11), rs.getObject(12, OffsetDateTime.class), rs.getString(13), rs.getString(14)), args);
    }
}
