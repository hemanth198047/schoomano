package com.schoomano.api;

import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/campuses")
public class CampusController {

    private final TenantTx tenantTx;
    private final ScopeDirectory scopes;
    private final Authorizer authz;
    private final Clock clock;

    public CampusController(TenantTx tenantTx, ScopeDirectory scopes, Authorizer authz, Clock clock) {
        this.tenantTx = tenantTx;
        this.scopes = scopes;
        this.authz = authz;
        this.clock = clock;
    }

    public record CampusView(UUID id, String code, String name, String timeZone, String status,
                             String legalEntity, String activeYear) { }

    /** Campuses the caller may read. RLS already confines rows to the caller's tenant. */
    @GetMapping
    public List<CampusView> list(RequestContext ctx) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            List<CampusView> all = jdbc.query("""
                select c.id, c.code, c.name, c.time_zone, c.status, le.name as legal_entity,
                       (select y.label from academic_year y where y.campus_id = c.id and y.status = 'ACTIVE'
                         order by y.starts_on desc limit 1) as active_year
                  from campus c join legal_entity le on le.id = c.legal_entity_id
                 order by c.name
                """, (rs, i) -> new CampusView(rs.getObject("id", UUID.class), rs.getString("code"),
                    rs.getString("name"), rs.getString("time_zone"), rs.getString("status"),
                    rs.getString("legal_entity"), rs.getString("active_year")));
            var now = clock.instant();
            return all.stream().filter(c -> scopes.campusPath(jdbc, ctx.tenant(), c.id())
                    .map(p -> authz.policy().decide(ctx.actor(), Permission.CAMPUS_READ,
                        Resource.ordinary("campus", c.id(), p, null), now).allowed())
                    .orElse(false))
                .toList();
        });
    }
}
