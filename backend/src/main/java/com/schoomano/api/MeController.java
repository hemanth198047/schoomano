package com.schoomano.api;

import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import com.schoomano.domain.access.AccessPolicy;
import com.schoomano.domain.access.GuardianCapability;
import com.schoomano.domain.access.GuardianLink;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Who am I, in which school, with which roles and which children (drives the context switcher). */
@RestController
@RequestMapping("/api/v1/me")
public class MeController {

    private final TenantTx tenantTx;
    private final Clock clock;

    public MeController(TenantTx tenantTx, Clock clock) {
        this.tenantTx = tenantTx;
        this.clock = clock;
    }

    /** The id is informational for building scope references; the server never trusts it as input scope. */
    public record TenantView(UUID id, String slug, String name) { }
    public record RoleView(String roleKey, String scopeLevel, UUID scopeId, String scopeName) { }
    public record ChildView(UUID studentId, String displayName, String schoolRef, UUID campusId, String campusName,
                            List<GuardianCapability> capabilities) { }
    public record MeView(UUID personId, String displayName, TenantView tenant, List<RoleView> roles,
                         List<ChildView> children, String policyVersion) { }

    @GetMapping
    public MeView me(RequestContext ctx) {
        var now = clock.instant();
        var actor = ctx.actor();
        return tenantTx.run(ctx.tenant(), jdbc -> {
            String name = jdbc.queryForObject(
                "select coalesce(preferred_name, given_name) || coalesce(' ' || family_name, '') from person where id = ?",
                String.class, actor.personId());

            List<RoleView> roles = actor.grants().stream().filter(g -> g.isActiveAt(now)).map(g -> {
                String scopeName = switch (g.scope().level()) {
                    case GROUP -> ctx.tenantName();
                    case CAMPUS -> jdbc.queryForList("select name from campus where id = ?", String.class, g.scope().id())
                        .stream().findFirst().orElse("Unknown campus");
                    default -> g.scope().level().name();
                };
                return new RoleView(g.roleKey(), g.scope().level().name(), g.scope().id(), scopeName);
            }).toList();

            List<ChildView> children = actor.guardianLinks().stream().filter(l -> l.isUsableAt(now))
                .map(l -> child(jdbc, l)).toList();

            return new MeView(actor.personId(), name, new TenantView(ctx.tenant().value(), ctx.tenantSlug(), ctx.tenantName()),
                roles, children, AccessPolicy.POLICY_VERSION);
        });
    }

    private static ChildView child(org.springframework.jdbc.core.JdbcTemplate jdbc, GuardianLink link) {
        return jdbc.queryForObject("""
            select coalesce(p.preferred_name, p.given_name) as display_name, p.school_ref, c.id as campus_id, c.name as campus_name
              from person p
              left join lateral (
                select e.campus_id from student_enrollment e join academic_year y on y.id = e.academic_year_id
                 where e.student_person_id = p.id order by y.starts_on desc limit 1) le on true
              left join campus c on c.id = le.campus_id
             where p.id = ?
            """, (rs, i) -> new ChildView(link.studentId(), rs.getString("display_name"), rs.getString("school_ref"),
                rs.getObject("campus_id", UUID.class), rs.getString("campus_name"),
                link.capabilities().stream().sorted().toList()), link.studentId());
    }
}
