package com.schoomano.api;

import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The operational checks of one school, shared by the health screen and the alerting job so both always say the same thing.
 * Each check says plainly whether something needs a person. Run inside the school's transaction.
 */
public final class OperationsChecks {

    private OperationsChecks() { }

    public static List<SupportController.Check> evaluate(JdbcTemplate jdbc) {
        List<SupportController.Check> checks = new ArrayList<>();

        long backlog = count(jdbc, "select count(*) from outbox_event where published_at is null");
        long oldest = count(jdbc, "select coalesce(extract(epoch from now() - min(occurred_at))::bigint, 0) from outbox_event where published_at is null");
        checks.add(new SupportController.Check("outbox", "Events waiting to be delivered", backlog == 0 ? "OK" : oldest > 600 ? "ATTENTION" : "OK",
            backlog == 0 ? "None waiting." : backlog + " waiting, the oldest for " + (oldest / 60) + " minutes."));

        long smsFailed = count(jdbc, "select count(*) from notice_delivery where channel = 'SMS' and status = 'FAILED' and created_at > now() - interval '7 days'");
        long smsWaiting = count(jdbc, "select count(*) from notice_delivery where channel = 'SMS' and status = 'PENDING' and send_after <= now()");
        checks.add(new SupportController.Check("sms", "Text messages", smsFailed > 0 ? "ATTENTION" : "OK",
            smsFailed + " failed in the last 7 days, " + smsWaiting + " due to go out."));

        long stale = count(jdbc, "select count(*) from payment where status = 'PENDING' and created_at < now() - interval '1 day'");
        long refunds = count(jdbc, "select count(*) from payment where status = 'NEEDS_REFUND'");
        checks.add(new SupportController.Check("payments", "Online payments", stale + refunds > 0 ? "ATTENTION" : "OK",
            refunds + " need a refund, " + stale + " started over a day ago and never confirmed."));

        long conflicts = count(jdbc, "select count(*) from attendance_conflict where state = 'OPEN'");
        checks.add(new SupportController.Check("attendance", "Attendance conflicts", conflicts > 0 ? "ATTENTION" : "OK",
            conflicts == 0 ? "None waiting for the office." : conflicts + " waiting for the office to settle."));

        long denied = count(jdbc, "select count(*) from audit_event where outcome = 'DENY' and occurred_at > now() - interval '1 day'");
        long events = count(jdbc, "select count(*) from audit_event where occurred_at > now() - interval '1 day'");
        checks.add(new SupportController.Check("audit", "Audit trail, last 24 hours", "OK", events + " events, " + denied + " refused."));

        long unreviewed = count(jdbc, "select count(*) from break_glass_grant where reviewed_at is null");
        long overdue = count(jdbc, "select count(*) from break_glass_grant where reviewed_at is null and granted_at < now() - interval '24 hours'");
        checks.add(new SupportController.Check("breakglass", "Emergency access reviews", overdue > 0 ? "ATTENTION" : "OK",
            unreviewed == 0 ? "Nothing waiting for review." : unreviewed + " waiting for review, " + overdue + " for more than a day."));

        int live = (int) count(jdbc, "select count(*) from support_access_grant where revoked_at is null and starts_at <= now() and expires_at > now()");
        checks.add(new SupportController.Check("support", "Platform support access", "OK",
            live == 0 ? "No one from support has access now." : live + " approved access open now."));
        return checks;
    }

    static long count(JdbcTemplate jdbc, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
