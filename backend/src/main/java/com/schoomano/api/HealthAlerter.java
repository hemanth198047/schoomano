package com.schoomano.api;

import com.schoomano.domain.tenancy.TenantId;
import com.schoomano.platform.TenantTx;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tells a school's administrators when an operational check starts needing attention, and again once a day while it still
 * does. The last state of each check is kept in the database and claimed with a single conditional write, so several
 * instances running together send one notice between them. Nothing is sent when a check recovers.
 */
@Component
public class HealthAlerter {

    private static final Logger log = LoggerFactory.getLogger(HealthAlerter.class);

    private final TenantTx tx;
    private final boolean enabled;
    private final int remindHours;

    public HealthAlerter(TenantTx tx, @Value("${schoomano.operations.alerter:true}") boolean enabled,
                         @Value("${schoomano.operations.remind-after-hours:24}") int remindHours) {
        this.tx = tx;
        this.enabled = enabled;
        this.remindHours = remindHours;
    }

    @Scheduled(fixedDelayString = "${schoomano.operations.poll-ms:900000}", initialDelayString = "${schoomano.operations.poll-ms:900000}")
    void tick() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("health alerting failed", e);
        }
    }

    /** One pass over every live school. Returns how many notices were sent. */
    public int runOnce() {
        List<UUID> tenants = tx.runWithoutTenant(jdbc -> jdbc.queryForList("select ops_alert_tenants()", UUID.class));
        int sent = 0;
        for (UUID tenant : tenants) {
            try {
                sent += tx.run(new TenantId(tenant), jdbc -> alert(jdbc));
            } catch (RuntimeException e) {
                log.warn("could not check school {}", tenant, e);
            }
        }
        return sent;
    }

    private int alert(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        UUID tenant = jdbc.queryForObject("select app_current_tenant()", UUID.class);
        List<SupportController.Check> checks = OperationsChecks.evaluate(jdbc);
        List<UUID> campuses = jdbc.queryForList("select id from campus order by created_at limit 1", UUID.class);
        List<UUID> admins = jdbc.queryForList("""
            select distinct person_id from role_grant where role_key = 'group_admin' and (valid_to is null or valid_to > now())
            """, UUID.class);
        int sent = 0;
        for (SupportController.Check c : checks) {
            if (!"ATTENTION".equals(c.level())) {
                jdbc.update("update ops_alert_state set level = 'OK', since = now() where tenant_id = ? and check_key = ? and level = 'ATTENTION'", tenant, c.key());
                continue;
            }
            // Claimed only if this check was fine before, or was last reported more than a day ago.
            boolean mine = !jdbc.queryForList("""
                insert into ops_alert_state (tenant_id, check_key, level, since, notified_at) values (?, ?, 'ATTENTION', now(), now())
                on conflict (tenant_id, check_key) do update
                   set since = case when ops_alert_state.level = 'ATTENTION' then ops_alert_state.since else now() end,
                       level = 'ATTENTION', notified_at = now()
                 where ops_alert_state.level = 'OK' or ops_alert_state.notified_at < now() - make_interval(hours => ?)
                returning check_key
                """, String.class, tenant, c.key(), remindHours).isEmpty();
            if (mine && !campuses.isEmpty() && !admins.isEmpty()) {
                String literal = "{" + String.join(",", admins.stream().map(UUID::toString).toList()) + "}";
                jdbc.queryForObject("select notice_tell_staff('OPERATIONS', ?, ?, ?, ?::uuid[], null)", Integer.class,
                    campuses.get(0), "Needs attention: " + c.label(), c.detail(), literal);
                sent++;
            }
        }
        return sent;
    }
}
