package com.schoomano.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tells staff about approvals in their app inbox: the holders of a step's role when a request reaches it, and the
 * requester when it is decided. Runs inside the caller's transaction, so a notice exists only if the change does.
 */
@Component
public class ApprovalNotifier {

    /** The request has reached its current step: tell everyone who holds that step's role, except the requester. */
    public void waiting(JdbcTemplate jdbc, UUID workflowRequestId) {
        Map<String, Object> r = jdbc.queryForMap("""
            select r.campus_id, r.subject, r.requester_id, s.label, s.role_key, d.name,
                   trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')) as requester
              from workflow_request r
              join workflow_definition d on d.tenant_id = r.tenant_id and d.id = r.definition_id
              join workflow_step s on s.tenant_id = r.tenant_id and s.definition_id = r.definition_id and s.position = r.current_step
              join person p on p.tenant_id = r.tenant_id and p.id = r.requester_id
             where r.id = ?
            """, workflowRequestId);
        List<UUID> holders = jdbc.queryForList("""
            select distinct person_id from role_grant where role_key = ? and person_id <> ? and (valid_to is null or valid_to > now())
            """, UUID.class, r.get("role_key"), r.get("requester_id"));
        tell(jdbc, (UUID) r.get("campus_id"), "Approval waiting: " + r.get("subject"),
            r.get("requester") + " asked for approval (" + r.get("name") + "). It is waiting for you as " + r.get("label") + ".", holders);
    }

    /** The request has waited too long: remind the people who hold its current step. */
    public void reminder(JdbcTemplate jdbc, UUID workflowRequestId) {
        Map<String, Object> r = overdue(jdbc, workflowRequestId);
        tell(jdbc, (UUID) r.get("campus_id"), "Reminder: " + r.get("subject"),
            r.get("requester") + "'s request has been waiting " + ago(r) + " for you as " + r.get("label") + ".",
            holders(jdbc, (UUID) r.get("campus_id"), List.of(String.valueOf(r.get("role_key"))), (UUID) r.get("requester_id")));
    }

    /** The request is still waiting: tell school leadership, and tell the requester that leadership knows. */
    public void escalated(JdbcTemplate jdbc, UUID workflowRequestId) {
        Map<String, Object> r = overdue(jdbc, workflowRequestId);
        UUID campus = (UUID) r.get("campus_id");
        UUID requester = (UUID) r.get("requester_id");
        tell(jdbc, campus, "Overdue approval: " + r.get("subject"),
            r.get("requester") + "'s request (" + r.get("name") + ") has been waiting " + ago(r) + " for " + r.get("label") + ".",
            holders(jdbc, campus, List.of("campus_admin", "principal", "group_admin"), requester));
        tell(jdbc, campus, "Still waiting: " + r.get("subject"),
            "Your request has been waiting " + ago(r) + " for " + r.get("label") + ". School leadership has been told.", List.of(requester));
    }

    private Map<String, Object> overdue(JdbcTemplate jdbc, UUID workflowRequestId) {
        return jdbc.queryForMap("""
            select r.campus_id, r.subject, r.requester_id, s.label, s.role_key, d.name,
                   trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')) as requester,
                   floor(extract(epoch from clock_timestamp() - coalesce((select max(x.decided_at) from workflow_decision x
                          where x.tenant_id = r.tenant_id and x.request_id = r.id), r.submitted_at)) / 3600)::int as hours
              from workflow_request r
              join workflow_definition d on d.tenant_id = r.tenant_id and d.id = r.definition_id
              join workflow_step s on s.tenant_id = r.tenant_id and s.definition_id = r.definition_id and s.position = r.current_step
              join person p on p.tenant_id = r.tenant_id and p.id = r.requester_id
             where r.id = ? and r.status = 'PENDING'
            """, workflowRequestId);
    }

    private static String ago(Map<String, Object> r) {
        int hours = ((Number) r.get("hours")).intValue();
        return hours < 48 ? Math.max(hours, 1) + (hours <= 1 ? " hour" : " hours") : hours / 24 + " days";
    }

    /** People holding one of the roles for this campus (or for the whole school), except the requester. */
    private List<UUID> holders(JdbcTemplate jdbc, UUID campusId, List<String> roles, UUID requester) {
        return jdbc.queryForList("""
            select distinct person_id from role_grant
             where role_key = any (?::text[]) and person_id <> ? and (valid_to is null or valid_to > now())
               and (scope_level = 'GROUP' or (scope_level = 'CAMPUS' and scope_id = ?))
            """, UUID.class, "{" + String.join(",", roles) + "}", requester, campusId);
    }

    /** The request is closed: tell the requester how it ended. */
    public void decided(JdbcTemplate jdbc, UUID workflowRequestId, String note) {
        Map<String, Object> r = jdbc.queryForMap("select campus_id, subject, requester_id, status from workflow_request where id = ?", workflowRequestId);
        String status = String.valueOf(r.get("status"));
        if (!"APPROVED".equals(status) && !"REJECTED".equals(status)) {
            return;
        }
        String word = "APPROVED".equals(status) ? "Approved" : "Rejected";
        tell(jdbc, (UUID) r.get("campus_id"), word + ": " + r.get("subject"),
            "Your request was " + word.toLowerCase() + "." + (note == null || note.isBlank() ? "" : " Reason: " + note.trim()),
            List.of((UUID) r.get("requester_id")));
    }

    private void tell(JdbcTemplate jdbc, UUID campusId, String title, String body, List<UUID> people) {
        if (people.isEmpty()) {
            return;
        }
        String literal = "{" + String.join(",", people.stream().map(UUID::toString).toList()) + "}";
        jdbc.queryForObject("select notice_tell_staff('APPROVAL', ?, ?, ?, ?::uuid[], null)", Integer.class, campusId, title, body, literal);
    }
}
