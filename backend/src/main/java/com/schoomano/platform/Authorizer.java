package com.schoomano.platform;

import com.schoomano.domain.access.AccessDecision;
import com.schoomano.domain.access.AccessPolicy;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.access.RoleCatalog;
import java.time.Clock;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Applies the core policy and records audited decisions (both allows and denials). */
@Component
public class Authorizer {

    private final AccessPolicy policy = new AccessPolicy(RoleCatalog.defaults());
    private final AuditWriter audit;
    private final Clock clock;

    public Authorizer(AuditWriter audit, Clock clock) {
        this.audit = audit;
        this.clock = clock;
    }

    public AccessDecision check(JdbcTemplate jdbc, RequestContext ctx, Permission permission, Resource resource) {
        AccessDecision d = policy.decide(ctx.actor(), permission, resource, clock.instant());
        if (d.mustAudit()) {
            audit.write(jdbc, ctx, "access." + permission.name().toLowerCase(), resource.type(),
                AuditWriter.id(resource.id()), d.allowed() ? "ALLOW" : "DENY", d.reason(),
                Map.of("basis", d.basis().name()));
        }
        return d;
    }

    /** Denial looks like absence, so callers cannot probe which records exist. */
    public void requireOrNotFound(JdbcTemplate jdbc, RequestContext ctx, Permission p, Resource r) {
        if (!check(jdbc, ctx, p, r).allowed()) {
            throw ApiException.notFound();
        }
    }

    public void requireOrForbidden(JdbcTemplate jdbc, RequestContext ctx, Permission p, Resource r) {
        if (!check(jdbc, ctx, p, r).allowed()) {
            throw ApiException.forbidden("You do not have permission for this action in this scope.");
        }
    }

    public AccessPolicy policy() {
        return policy;
    }
}
