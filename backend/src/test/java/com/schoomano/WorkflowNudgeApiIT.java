package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.schoomano.api.ApprovalNudger;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B35: approvals that wait too long are reminded once, escalated once, and left alone once decided. */
class WorkflowNudgeApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";

    @Autowired
    ApprovalNudger nudger;

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void staff(String id, String subject, String role) throws Exception {
        ownerUpdate("""
            insert into person (id, tenant_id, school_ref, given_name, family_name) values ('13000000-0000-4000-8000-000000000%1$s', '%2$s', 'STF-IT-%1$s', '%3$s', 'Staff')
              on conflict (id) do nothing;
            insert into identity (tenant_id, person_id, issuer, subject, email)
              values ('%2$s', '13000000-0000-4000-8000-000000000%1$s', 'dev', '%3$s', '%3$s@example.invalid') on conflict do nothing;
            insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason)
              select '%2$s', '13000000-0000-4000-8000-000000000%1$s', '%4$s', 'CAMPUS', '%5$s', 'it'
               where not exists (select 1 from role_grant where person_id = '13000000-0000-4000-8000-000000000%1$s');
            """.formatted(id, TENANT, subject, role, CAMPUS));
    }

    private static long told(String person, String title) throws Exception {
        return ownerCount("select count(*) from notice_delivery d join notice n on n.id = d.notice_id where n.kind = 'APPROVAL' and d.channel = 'IN_APP' and n.title = '"
            + title + "' and d.recipient_person_id = '13000000-0000-4000-8000-000000000" + person + "'");
    }

    private String create(String url, String who, String body) throws Exception {
        return mvc.perform(json(post(url), who, body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void remindsOnceEscalatesOnceAndStopsWhenDecided() throws Exception {
        staff("d71", "dev-nu-d71", "campus_admin");
        staff("d72", "dev-nu-d72", "teacher");
        staff("d73", "dev-nu-d73", "principal");
        staff("d74", "dev-nu-d74", "finance_officer");
        String chain = create("/api/v1/campuses/" + CAMPUS + "/workflow-chains", "dev-nu-d71",
            "{\"chainKey\":\"it-nudge\",\"name\":\"IT Nudge\",\"steps\":[{\"roleKey\":\"principal\",\"label\":\"Principal\"},{\"roleKey\":\"finance_officer\",\"label\":\"Finance\"}]}");
        mvc.perform(json(post("/api/v1/workflow-chains/" + JsonPath.read(chain, "$.id") + "/publish"), "dev-nu-d71", "{}")).andExpect(status().isOk());
        String reqId = JsonPath.read(create("/api/v1/campuses/" + CAMPUS + "/workflow-requests", "dev-nu-d72",
            "{\"chainId\":\"" + JsonPath.read(chain, "$.id") + "\",\"subject\":\"IT Nudge laptop\"}"), "$.id");
        long nudges = 0;

        // Nothing is overdue yet with the normal waiting times.
        nudger.runOnce(Duration.ofHours(24), Duration.ofHours(72));
        assertEquals(0, ownerCount("select count(*) from workflow_nudge where request_id = '" + reqId + "'"));

        // A reminder reaches the step's holder, once, and nobody else.
        assertTrue(nudger.runOnce(Duration.ZERO, Duration.ofDays(3)) >= 1);
        nudger.runOnce(Duration.ZERO, Duration.ofDays(3));
        assertEquals(1, told("d73", "Reminder: IT Nudge laptop"));
        assertEquals(0, told("d74", "Reminder: IT Nudge laptop"));
        assertEquals(0, told("d72", "Reminder: IT Nudge laptop"));

        // Escalation goes to leadership and tells the requester, once.
        nudger.runOnce(Duration.ZERO, Duration.ZERO);
        nudger.runOnce(Duration.ZERO, Duration.ZERO);
        assertEquals(1, told("d71", "Overdue approval: IT Nudge laptop"));
        assertEquals(1, told("d73", "Overdue approval: IT Nudge laptop"));
        assertEquals(0, told("d72", "Overdue approval: IT Nudge laptop"));
        assertEquals(1, told("d72", "Still waiting: IT Nudge laptop"));
        assertEquals(0, told("d74", "Overdue approval: IT Nudge laptop"));

        // After the principal approves, the wait restarts and the next step's holder is reminded.
        mvc.perform(json(post("/api/v1/workflow-requests/" + reqId + "/decision"), "dev-nu-d73", "{\"decision\":\"APPROVED\"}")).andExpect(status().isOk());
        nudger.runOnce(Duration.ofHours(24), Duration.ofHours(72));
        assertEquals(0, told("d74", "Reminder: IT Nudge laptop"));
        nudger.runOnce(Duration.ZERO, Duration.ofDays(3));
        assertEquals(1, told("d74", "Reminder: IT Nudge laptop"));
        assertEquals(1, told("d73", "Reminder: IT Nudge laptop"));
        nudges = ownerCount("select count(*) from workflow_nudge where request_id = '" + reqId + "'");
        assertEquals(3, nudges);

        // Once decided, nothing more is sent.
        mvc.perform(json(post("/api/v1/workflow-requests/" + reqId + "/decision"), "dev-nu-d74", "{\"decision\":\"APPROVED\"}")).andExpect(status().isOk());
        nudger.runOnce(Duration.ZERO, Duration.ZERO);
        assertEquals(3, ownerCount("select count(*) from workflow_nudge where request_id = '" + reqId + "'"));
    }
}
