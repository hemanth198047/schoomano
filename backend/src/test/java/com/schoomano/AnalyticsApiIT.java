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

/** B24: campus summaries calculated from enrolment, attendance and fees, shown only to those who run the campus. */
class AnalyticsApiIT extends ApiITSupport {

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
    void summariesMatchTheUnderlyingRecordsAndAreForCampusLeadersOnly() throws Exception {
        staff("e21", "dev-an-e21", "campus_admin");
        String url = "/api/v1/academic-years/" + YEAR + "/analytics";
        String day = "2026-09-16";
        String window = "?from=" + day + "&to=" + day;

        // Who may look: the office and the group admin; teachers, parents and other schools get nothing.
        mvc.perform(as(get(url), "dev-ravi")).andExpect(status().isNotFound());
        mvc.perform(as(get(url), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(get(url), "dev-other")).andExpect(status().isNotFound());
        mvc.perform(as(get(url + "?from=2026-01-01&to=2026-12-01"), "dev-an-e21")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_range"));
        mvc.perform(as(get(url), "dev-asha")).andExpect(status().isOk()).andExpect(jsonPath("$.yearId").value(YEAR));

        // Attendance: one child marked present on a quiet day shows as one mark and 100 percent for that day.
        mvc.perform(as(get(url + window), "dev-an-e21")).andExpect(status().isOk())
            .andExpect(jsonPath("$.attendance.marks").value(0)).andExpect(jsonPath("$.attendance.percent").doesNotExist());
        String section = ownerString("select md5('section-CBSE-K12-5-A')::uuid::text");
        mvc.perform(json(post("/api/v1/sections/" + section + "/attendance/batches"), "dev-ravi",
            "{\"date\":\"" + day + "\",\"source\":\"DEVICE\",\"marks\":[{\"studentId\":\"" + ANAYA + "\",\"status\":\"PRESENT\",\"baseVersion\":0}]}")
            .header("Idempotency-Key", "key-an-0001")).andExpect(status().is2xxSuccessful());
        mvc.perform(as(get(url + window), "dev-an-e21")).andExpect(status().isOk())
            .andExpect(jsonPath("$.attendance.marks").value(1)).andExpect(jsonPath("$.attendance.attended").value(1))
            .andExpect(jsonPath("$.attendance.absent").value(0)).andExpect(jsonPath("$.attendance.percent").value(100.0))
            .andExpect(jsonPath("$.attendance.days.length()").value(1)).andExpect(jsonPath("$.attendance.sections.length()").value(1))
            .andExpect(jsonPath("$.attendance.sessions").value(1));

        // Fees: an overdue invoice adds to billed, outstanding and overdue by exactly its amount.
        long billed = ((Number) JsonPath.read(mvc.perform(as(get(url), "dev-an-e21")).andReturn().getResponse().getContentAsString(), "$.fees.billedPaise")).longValue();
        long overdue = ((Number) JsonPath.read(mvc.perform(as(get(url), "dev-an-e21")).andReturn().getResponse().getContentAsString(), "$.fees.overduePaise")).longValue();
        ownerUpdate("""
            insert into invoice (id, tenant_id, campus_id, academic_year_id, student_person_id, number, due_on, total_paise, issued_by)
              values ('1a000000-0000-4000-8000-0000000000a1', '%1$s', '%2$s', '%3$s', '%4$s', 'IT-AN-0001', current_date - 3, 12345, '13000000-0000-4000-8000-000000000001');
            insert into invoice_line (tenant_id, invoice_id, description, amount_paise)
              values ('%1$s', '1a000000-0000-4000-8000-0000000000a1', 'Analytics test', 12345);
            """.formatted(TENANT, CAMPUS, YEAR, ANAYA));
        mvc.perform(as(get(url), "dev-an-e21")).andExpect(status().isOk())
            .andExpect(jsonPath("$.fees.billedPaise").value(billed + 12345)).andExpect(jsonPath("$.fees.overduePaise").value(overdue + 12345));

        // Enrolment counts exist, and nothing in the response names a child.
        String all = mvc.perform(as(get(url), "dev-an-e21")).andReturn().getResponse().getContentAsString();
        assertEquals(false, all.contains(ANAYA));
        assertEquals(false, all.contains("Anaya"));
        mvc.perform(as(get(url), "dev-an-e21")).andExpect(jsonPath("$.enrolment.enrolled").isNumber());
    }
}
