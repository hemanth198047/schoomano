package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.access.RoleCatalog;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 * Who works at the school and what each person may do (ROLE_GRANT_MANAGE, held by the school group's administrators). A role is
 * given for the whole group or for one campus, with a reason; ending it keeps the record. The group's last administrator
 * cannot be taken away, so the school can never lock itself out. Everything is audited.
 */
@RestController
@RequestMapping("/api/v1/staff")
public class StaffController {

    /** Roles a person cannot be given here: the read-only identity used by integration keys. */
    private static final Set<String> NOT_FOR_PEOPLE = Set.of("integration_reader");

    /** Roles that always belong to one campus. */
    private static final Set<String> CAMPUS_ONLY = Set.of("teacher", "nurse", "front_desk", "principal", "campus_admin", "safeguarding_lead");

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;

    public StaffController(TenantTx tenantTx, Authorizer authz, AuditWriter audit) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
    }

    public record StaffCreate(@NotBlank @Size(max = 80) String givenName, @Size(max = 80) String familyName, @NotBlank @Email @Size(max = 200) String email) { }

    public record RoleGrantCreate(@NotBlank @Pattern(regexp = "^[a-z][a-z0-9_]{2,40}$") String roleKey, @NotNull @Pattern(regexp = "GROUP|CAMPUS") String scopeLevel,
                                  UUID scopeId, @NotBlank @Size(min = 10, max = 300) String reason) { }

    public record EndRequest(@NotBlank @Size(min = 10, max = 300) String reason) { }

    public record RoleView(UUID grantId, String roleKey, String scopeLevel, UUID scopeId, String scopeName, OffsetDateTime validFrom, OffsetDateTime validTo, String reason) { }

    public record StaffView(UUID personId, String displayName, String email, String status, List<RoleView> roles) { }

    @GetMapping
    public List<StaffView> list(RequestContext ctx) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            return load(jdbc, ctx, null);
        });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public StaffView create(RequestContext ctx, @Valid @RequestBody StaffCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            String email = req.email().trim().toLowerCase(java.util.Locale.ROOT);
            if (jdbc.queryForObject("select count(*) from person where provenance->>'kind' = 'STAFF' and provenance->>'email' = ?", Integer.class, email) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_listed", "Someone with this email is already on the staff list.");
            }
            String ref = "STF-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
            UUID id = jdbc.queryForObject("""
                insert into person (tenant_id, school_ref, given_name, family_name, provenance, created_by)
                values (?, ?, ?, ?, jsonb_build_object('kind', 'STAFF', 'email', ?::text), ?) returning id
                """, UUID.class, ctx.tenant().value(), ref, req.givenName().trim(), blankToNull(req.familyName()), email, ctx.actor().personId());
            audit.write(jdbc, ctx, "staff.added", "person", id.toString(), "SUCCESS", null, Map.of("schoolRef", ref));
            return load(jdbc, ctx, id).get(0);
        });
    }

    @PostMapping("/{personId}/roles")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffView grant(RequestContext ctx, @PathVariable UUID personId, @Valid @RequestBody RoleGrantCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            if (jdbc.queryForList("select id from person where id = ? and provenance->>'kind' = 'STAFF'", UUID.class, personId).isEmpty()) {
                throw ApiException.notFound();
            }
            if (!RoleCatalog.defaults().roleKeys().contains(req.roleKey()) || NOT_FOR_PEOPLE.contains(req.roleKey())) {
                throw ApiException.badRequest("unknown_role", "That role does not exist.");
            }
            UUID scope;
            if ("GROUP".equals(req.scopeLevel())) {
                scope = ctx.tenant().value();
            } else {
                if ("group_admin".equals(req.roleKey())) {
                    throw ApiException.badRequest("group_role", "The group administrator role covers the whole group.");
                }
                if (req.scopeId() == null || jdbc.queryForList("select id from campus where id = ?", UUID.class, req.scopeId()).isEmpty()) {
                    throw ApiException.badRequest("unknown_campus", "Choose one of the school's campuses.");
                }
                scope = req.scopeId();
            }
            if ("GROUP".equals(req.scopeLevel()) && CAMPUS_ONLY.contains(req.roleKey())) {
                throw ApiException.badRequest("campus_role", "This role is given for one campus.");
            }
            Integer dup = jdbc.queryForObject("""
                select count(*) from role_grant where person_id = ? and role_key = ? and scope_level = ? and scope_id = ?
                   and valid_from <= now() and (valid_to is null or valid_to > now())
                """, Integer.class, personId, req.roleKey(), req.scopeLevel(), scope);
            if (dup != null && dup > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_has_role", "This person already has that role there.");
            }
            UUID id = jdbc.queryForObject("""
                insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, granted_by, reason)
                values (?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), personId, req.roleKey(), req.scopeLevel(), scope, ctx.actor().personId(), req.reason().trim());
            audit.write(jdbc, ctx, "staff.role_granted", "person", personId.toString(), "SUCCESS", req.reason().trim(),
                Map.of("grantId", id.toString(), "role", req.roleKey(), "scopeLevel", req.scopeLevel(), "scopeId", scope.toString()));
            return load(jdbc, ctx, personId).get(0);
        });
    }

    @PostMapping("/{personId}/roles/{grantId}/end")
    public StaffView end(RequestContext ctx, @PathVariable UUID personId, @PathVariable UUID grantId, @Valid @RequestBody EndRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            List<Map<String, Object>> rows = jdbc.queryForList("""
                select role_key from role_grant where id = ? and person_id = ? and (valid_to is null or valid_to > now())
                """, grantId, personId);
            if (rows.isEmpty()) {
                throw ApiException.notFound();
            }
            if ("group_admin".equals(rows.get(0).get("role_key"))) {
                Integer others = jdbc.queryForObject("""
                    select count(distinct person_id) from role_grant where role_key = 'group_admin' and id <> ?
                       and valid_from <= now() and (valid_to is null or valid_to > now())
                    """, Integer.class, grantId);
                if (others == null || others == 0) {
                    throw new ApiException(HttpStatus.CONFLICT, "last_administrator", "The group needs at least one administrator. Give the role to someone else first.");
                }
            }
            jdbc.update("update role_grant set valid_to = greatest(now(), valid_from + interval '1 second') where id = ?", grantId);
            audit.write(jdbc, ctx, "staff.role_ended", "person", personId.toString(), "SUCCESS", req.reason().trim(), Map.of("grantId", grantId.toString()));
            return load(jdbc, ctx, personId).get(0);
        });
    }

    private void require(JdbcTemplate jdbc, RequestContext ctx) {
        authz.requireOrNotFound(jdbc, ctx, Permission.ROLE_GRANT_MANAGE, Resource.ordinary("role_grant", null, ScopePath.group(ctx.tenant()), null));
    }

    private List<StaffView> load(JdbcTemplate jdbc, RequestContext ctx, UUID only) {
        Map<UUID, StaffView> out = new LinkedHashMap<>();
        jdbc.query("""
            select p.id, trim(p.given_name || ' ' || coalesce(p.family_name, '')), p.provenance->>'email', p.status
              from person p
             where (p.provenance->>'kind' = 'STAFF' or exists (select 1 from role_grant g where g.person_id = p.id))
               and (?::uuid is null or p.id = ?::uuid)
             order by 2, p.id
            """, rs -> {
            out.put(rs.getObject(1, UUID.class), new StaffView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), new ArrayList<>()));
        }, only, only);
        jdbc.query("""
            select g.person_id, g.id, g.role_key, g.scope_level, g.scope_id, coalesce(c.name, t.display_name), g.valid_from, g.valid_to, g.reason
              from role_grant g left join campus c on g.scope_level = 'CAMPUS' and c.id = g.scope_id
              left join tenant t on g.scope_level = 'GROUP' and t.id = g.scope_id
             where g.valid_from <= now() and (g.valid_to is null or g.valid_to > now())
             order by g.valid_from, g.id
            """, rs -> {
            StaffView v = out.get(rs.getObject(1, UUID.class));
            if (v != null) {
                v.roles().add(new RoleView(rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6),
                    rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), rs.getString(9)));
            }
        });
        return new ArrayList<>(out.values());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
