package com.schoomano;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B15: anyone on staff can report; only the designated lead (Farah, North) can read and act. */
class SafeguardingApiIT extends ApiITSupport {

    static final String YEAR = "15000000-0000-4000-8000-000000000001";
    static final String ANAYA = "13000000-0000-4000-8000-000000000020";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private static String report(String severity, String text) {
        return "{\"category\":\"PHYSICAL\",\"severity\":\"" + severity + "\",\"description\":\"" + text + "\"}";
    }

    @Test
    void reportersGetAReceiptAndOnlyTheLeadCanReadAndAct() throws Exception {
        String url = "/api/v1/students/" + ANAYA + "/safeguarding-reports";

        // Reporting: staff can; guardians, non-students and bad input cannot.
        mvc.perform(json(post(url), "dev-meera", report("HIGH", "Child came in with bruises on arm"))).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/students/13000000-0000-4000-8000-000000000004/safeguarding-reports"), "dev-ravi",
            report("HIGH", "Not a student at all"))).andExpect(status().isNotFound());
        mvc.perform(json(post(url), "dev-ravi", report("HIGH", "short"))).andExpect(status().isBadRequest());
        mvc.perform(json(post(url), "dev-ravi",
            "{\"category\":\"PHYSICAL\",\"severity\":\"HIGH\",\"description\":\"Dated in the future for no reason\",\"occurredOn\":\"2999-01-01\"}"))
            .andExpect(status().isBadRequest());
        String high = JsonPath.read(mvc.perform(json(post(url), "dev-ravi", report("HIGH", "Child came in with bruises on arm")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.message").isNotEmpty()).andReturn().getResponse().getContentAsString(), "$.id");
        String low = JsonPath.read(mvc.perform(json(post(url), "dev-asha", report("LOW", "Seems tired and quiet this week")))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        ownerUpdate("""
            insert into safeguarding_report (id, tenant_id, student_person_id, category, severity, description, reported_by, reported_at)
            values ('13000000-0000-4000-8000-0000000b1501', '10000000-0000-4000-8000-000000000001', '%s', 'ONLINE', 'HIGH',
                    'Seen sending upsetting messages online', '13000000-0000-4000-8000-000000000004', now() - interval '6 hours')
            on conflict do nothing""".formatted(ANAYA));

        // Reporters, the principal's superiors and the child's guardian cannot read what was reported.
        for (String who : new String[] {"dev-ravi", "dev-asha", "dev-meera", "dev-karan"}) {
            mvc.perform(as(get("/api/v1/safeguarding-reports/" + high), who)).andExpect(status().isNotFound());
            mvc.perform(as(get("/api/v1/academic-years/" + YEAR + "/safeguarding-reports"), who)).andExpect(status().isNotFound());
        }
        mvc.perform(as(get("/api/v1/safeguarding-reports/" + high), "dev-other")).andExpect(status().isNotFound());

        // The lead sees them, most severe first, with overdue triage flagged.
        mvc.perform(as(get("/api/v1/academic-years/" + YEAR + "/safeguarding-reports"), "dev-farah")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + high + "')].triageOverdue").value(hasItem(false)))
            .andExpect(jsonPath("$[?(@.id=='13000000-0000-4000-8000-0000000b1501')].triageOverdue").value(hasItem(true)))
            .andExpect(jsonPath("$[?(@.id=='" + low + "')]", hasSize(1)));

        // Handling follows the steps; escalating or closing needs a note; closed is final.
        String act = "/api/v1/safeguarding-reports/" + high + "/actions";
        mvc.perform(json(post(act), "dev-ravi", "{\"note\":\"I would like to add something\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(act), "dev-asha", "{\"toStatus\":\"CLOSED\",\"note\":\"Closing it myself\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(act), "dev-farah", "{}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(act), "dev-farah", "{\"toStatus\":\"ESCALATED\",\"note\":\"Skipping ahead\"}")).andExpect(status().isConflict());
        mvc.perform(json(post(act), "dev-farah", "{\"toStatus\":\"TRIAGED\",\"note\":\"Spoke with the class teacher\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.summary.status").value("TRIAGED"));
        mvc.perform(json(post(act), "dev-farah", "{\"toStatus\":\"UNDER_REVIEW\"}")).andExpect(status().isOk());
        mvc.perform(json(post(act), "dev-farah", "{\"toStatus\":\"ESCALATED\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(act), "dev-farah", "{\"toStatus\":\"ESCALATED\",\"note\":\"Referred to child protection services, ref 4471\"}")).andExpect(status().isOk());
        mvc.perform(json(post(act), "dev-farah", "{\"toStatus\":\"CLOSED\",\"note\":\"Authority took over the case\"}")).andExpect(status().isOk());
        mvc.perform(json(post(act), "dev-farah", "{\"note\":\"Afterthought\"}")).andExpect(status().isConflict());
        mvc.perform(as(get("/api/v1/safeguarding-reports/" + high), "dev-farah")).andExpect(status().isOk())
            .andExpect(jsonPath("$.actions", hasSize(4))).andExpect(jsonPath("$.summary.status").value("CLOSED"))
            .andExpect(jsonPath("$.description").value("Child came in with bruises on arm"));

        // Reading and acting leave a trail.
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'safeguarding.viewed' and resource_id = '" + high + "'"));
        assertEquals(4, ownerCount("select count(*) from audit_event where action = 'safeguarding.acted' and resource_id = '" + high + "'"));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'safeguarding.reported' and resource_id = '" + low + "'"));
    }
}
