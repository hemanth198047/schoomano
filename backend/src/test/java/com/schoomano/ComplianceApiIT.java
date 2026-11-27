package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B23: the campus compliance register: obligations, owners, evidence that moves the due date. */
class ComplianceApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";
    static final String YEAR = "15000000-0000-4000-8000-000000000001";
    static final String ANAYA = "13000000-0000-4000-8000-000000000020";
    static final String YEAR2_ONLY = "13000000-0000-4000-8000-000000000021";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
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

    private String create(String url, String who, String body) throws Exception {
        return mvc.perform(json(post(url), who, body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void campusKeepsObligationsAndEvidenceMovesTheDueDate() throws Exception {
        staff("c01", "dev-cp-c01", "campus_admin");
        staff("c02", "dev-cp-c02", "principal");
        staff("c03", "dev-cp-c03", "teacher");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        String items = "/api/v1/campuses/" + CAMPUS + "/compliance-items";
        String fire = "{\"title\":\"IT Fire certificate\",\"category\":\"FIRE_SAFETY\",\"ownerName\":\"Estates head\",\"repeatMonths\":12,\"firstDueOn\":\"" + today.minusDays(5) + "\"}";

        // Only the office adds items; teachers and parents see nothing; the principal looks and records evidence.
        mvc.perform(json(post(items), "dev-cp-c03", fire)).andExpect(status().isNotFound());
        mvc.perform(json(post(items), "dev-meera", fire)).andExpect(status().isNotFound());
        mvc.perform(json(post(items), "dev-cp-c02", fire)).andExpect(status().isNotFound());
        mvc.perform(as(get(items), "dev-cp-c03")).andExpect(status().isNotFound());
        mvc.perform(json(post(items), "dev-cp-c01", fire.replace("FIRE_SAFETY", "MAGIC"))).andExpect(status().isBadRequest());
        String fireId = JsonPath.read(create(items, "dev-cp-c01", fire), "$.id");
        mvc.perform(json(post(items), "dev-cp-c01", fire)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_item"));
        mvc.perform(as(get(items), "dev-cp-c02")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + fireId + "')].standing").value("OVERDUE"));

        // Evidence moves the due date by the repeat interval; the future and backwards expiry are refused.
        String evidence = "/api/v1/compliance-items/" + fireId + "/evidence";
        mvc.perform(json(post(evidence), "dev-cp-c03", "{\"doneOn\":\"" + today + "\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(evidence), "dev-cp-c02", "{\"doneOn\":\"" + today.plusDays(2) + "\"}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_evidence"));
        mvc.perform(json(post(evidence), "dev-cp-c02", "{\"doneOn\":\"" + today + "\",\"validUntil\":\"" + today.minusDays(1) + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(evidence), "dev-cp-c02", "{\"doneOn\":\"" + today + "\",\"reference\":\"FS-114\",\"note\":\"Inspected\"}")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.standing").value("CURRENT")).andExpect(jsonPath("$.nextDueOn").value(today.plusMonths(12).toString()))
            .andExpect(jsonPath("$.lastReference").value("FS-114")).andExpect(jsonPath("$.evidenceCount").value(1));
        mvc.perform(as(get(evidence), "dev-cp-c02")).andExpect(status().isOk()).andExpect(jsonPath("$[0].reference").value("FS-114"));
        mvc.perform(as(get(evidence), "dev-cp-c03")).andExpect(status().isNotFound());

        // Earlier evidence never pulls the due date back.
        mvc.perform(json(post(evidence), "dev-cp-c02", "{\"doneOn\":\"" + today.minusMonths(6) + "\"}")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.nextDueOn").value(today.plusMonths(12).toString()));

        // A one-off item is done once evidence is recorded; an expiry sets the next due date.
        String once = JsonPath.read(create(items, "dev-cp-c01", "{\"title\":\"IT Privacy review\",\"category\":\"PRIVACY\",\"ownerName\":\"DPO\",\"firstDueOn\":\""
            + today.plusDays(10) + "\"}"), "$.id");
        mvc.perform(as(get(items), "dev-cp-c01")).andExpect(jsonPath("$[?(@.id=='" + once + "')].standing").value("DUE_SOON"));
        mvc.perform(json(post("/api/v1/compliance-items/" + once + "/evidence"), "dev-cp-c02", "{\"doneOn\":\"" + today + "\"}")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.standing").value("DONE")).andExpect(jsonPath("$.nextDueOn").doesNotExist());
        String licence = JsonPath.read(create(items, "dev-cp-c01", "{\"title\":\"IT Licence\",\"category\":\"STATUTORY\",\"ownerName\":\"Admin\",\"firstDueOn\":\""
            + today.plusDays(3) + "\"}"), "$.id");
        mvc.perform(json(post("/api/v1/compliance-items/" + licence + "/evidence"), "dev-cp-c02", "{\"doneOn\":\"" + today + "\",\"validUntil\":\"" + today.plusYears(3) + "\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.nextDueOn").value(today.plusYears(3).toString())).andExpect(jsonPath("$.standing").value("CURRENT"));

        // Retiring: office only, once; no more evidence afterwards; other schools see nothing.
        mvc.perform(post("/api/v1/compliance-items/" + once + "/retire").header("Authorization", dev("dev-cp-c02"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/compliance-items/" + once + "/retire").header("Authorization", dev("dev-cp-c01"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.standing").value("RETIRED"));
        mvc.perform(post("/api/v1/compliance-items/" + once + "/retire").header("Authorization", dev("dev-cp-c01"))).andExpect(status().isConflict());
        mvc.perform(json(post("/api/v1/compliance-items/" + once + "/evidence"), "dev-cp-c02", "{\"doneOn\":\"" + today + "\"}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("item_retired"));
        mvc.perform(as(get(items), "dev-other")).andExpect(status().isNotFound());
    }
}
