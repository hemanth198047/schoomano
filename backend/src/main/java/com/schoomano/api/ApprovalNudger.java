package com.schoomano.api;

import com.schoomano.domain.tenancy.TenantId;
import com.schoomano.platform.TenantTx;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nudges approvals that wait too long: a reminder to the people who hold the current step after a day, and an escalation
 * to school leadership after three. Each nudge is recorded together with its notice in one transaction, so it is sent once
 * per step even with several instances running.
 */
@Component
public class ApprovalNudger {

    private static final Logger log = LoggerFactory.getLogger(ApprovalNudger.class);

    private record Overdue(UUID tenant, UUID request, int step, String kind) { }

    private final TenantTx tx;
    private final ApprovalNotifier notifier;
    private final boolean enabled;
    private final Duration remind;
    private final Duration escalate;

    public ApprovalNudger(TenantTx tx, ApprovalNotifier notifier, @Value("${schoomano.workflow.nudger:true}") boolean enabled,
                          @Value("${schoomano.workflow.remind-after-hours:24}") long remindHours,
                          @Value("${schoomano.workflow.escalate-after-hours:72}") long escalateHours) {
        this.tx = tx;
        this.notifier = notifier;
        this.enabled = enabled;
        this.remind = Duration.ofHours(remindHours);
        this.escalate = Duration.ofHours(escalateHours);
    }

    @Scheduled(fixedDelayString = "${schoomano.workflow.poll-ms:900000}", initialDelayString = "${schoomano.workflow.poll-ms:900000}")
    void tick() {
        if (!enabled) {
            return;
        }
        try {
            runOnce(remind, escalate);
        } catch (RuntimeException e) {
            log.error("approval nudges failed", e);
        }
    }

    /** One pass with the given waiting times. Returns how many nudges were sent. */
    public int runOnce(Duration remindAfter, Duration escalateAfter) {
        List<Overdue> due = tx.runWithoutTenant(jdbc -> jdbc.query(
            "select t_id, req_id, step_no, nudge_kind from workflow_overdue(?::interval, ?::interval)",
            (rs, i) -> new Overdue(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4)),
            remindAfter.toString(), escalateAfter.toString()));
        int sent = 0;
        for (Overdue o : due) {
            try {
                boolean done = tx.run(new TenantId(o.tenant()), jdbc -> {
                    if (jdbc.update("insert into workflow_nudge (tenant_id, request_id, position, kind) values (?, ?, ?, ?) on conflict do nothing",
                        o.tenant(), o.request(), o.step(), o.kind()) == 0) {
                        return false;
                    }
                    if ("ESCALATION".equals(o.kind())) {
                        notifier.escalated(jdbc, o.request());
                    } else {
                        notifier.reminder(jdbc, o.request());
                    }
                    return true;
                });
                if (done) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("could not nudge request {}", o.request(), e);
            }
        }
        return sent;
    }
}
