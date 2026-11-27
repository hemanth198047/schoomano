package com.schoomano;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B18: HR keeps staff profiles and allowances; staff request leave that someone else approves within the balance. */
class HrApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";
    static final String YEAR = "15000000-0000-4000-8000-000000000001";
    static final String ANAYA = "13000000-0000-4000-8000-000000000020";

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

    private static String profile(String no) {
        return "{\"employeeNo\":\"" + no + "\",\"designation\":\"Teacher\",\"employmentType\":\"FULL_TIME\",\"joinedOn\":\"2020-04-01\"}";
    }

    private static String leave(String type, String from, String to) {
        return "{\"leaveType\":\"" + type + "\",\"fromDate\":\"" + from + "\",\"toDate\":\"" + to + "\",\"reason\":\"Family function\"}";
    }

    @Test
    void hrKeepsProfilesAndStaffLeaveIsApprovedByAnotherWithinBalance() throws Exception {
        staff("c01", "dev-hr-c01", "campus_admin");
        staff("c02", "dev-prin-c02", "principal");
        staff("c03", "dev-tina-c03", "teacher");
        String c03 = "13000000-0000-4000-8000-000000000c03";
        String c02 = "13000000-0000-4000-8000-000000000c02";
        String requests = "/api/v1/academic-years/" + YEAR + "/leave-requests";

        // Profiles: HR only; employee numbers are unique; students cannot have one.
        mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + c03), "dev-tina-c03", profile("E-IT-C03"))).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + c03), "dev-meera", profile("E-IT-C03"))).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + c03), "dev-hr-c01", profile("E-IT-C03"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("dev-tina-c03 Staff")).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + c02), "dev-hr-c01", profile("E-IT-C03"))).andExpect(status().isConflict());
        mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + c02), "dev-hr-c01", profile("E-IT-C02"))).andExpect(status().isOk());
        mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + ANAYA), "dev-hr-c01", profile("E-IT-ANAYA"))).andExpect(status().isBadRequest());
        mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + c03), "dev-hr-c01",
            profile("E-IT-C03").replace("}", ",\"leftOn\":\"2019-01-01\"}"))).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/people?q=tina-c03"), "dev-hr-c01")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.personId=='" + c03 + "')].hasProfile").value(hasItem(true)));
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/people?q=Anaya"), "dev-hr-c01")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/people?q=t"), "dev-hr-c01")).andExpect(status().isBadRequest());
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/people?q=tina"), "dev-tina-c03")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/staff"), "dev-hr-c01")).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].employeeNo").value(hasItem("E-IT-C03")));
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/staff"), "dev-tina-c03")).andExpect(status().isNotFound());

        // Allowances: HR only.
        String allowance = "{\"leaveType\":\"CASUAL\",\"days\":4}";
        mvc.perform(json(put("/api/v1/academic-years/" + YEAR + "/leave-allowances"), "dev-tina-c03", allowance)).andExpect(status().isNotFound());
        mvc.perform(json(put("/api/v1/academic-years/" + YEAR + "/leave-allowances"), "dev-hr-c01", allowance)).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.leaveType=='CASUAL')].allowance").value(hasItem(4.0)));

        // Requesting: staff with a profile only; Sundays do not count; dates must make sense.
        mvc.perform(json(post(requests), "dev-meera", leave("CASUAL", "2026-12-07", "2026-12-09"))).andExpect(status().isNotFound());
        mvc.perform(json(post(requests), "dev-ravi", leave("CASUAL", "2026-12-07", "2026-12-09"))).andExpect(status().isBadRequest());
        mvc.perform(json(post(requests), "dev-tina-c03", leave("CASUAL", "2026-12-09", "2026-12-07"))).andExpect(status().isBadRequest());
        mvc.perform(json(post(requests), "dev-tina-c03", leave("CASUAL", "2028-12-07", "2028-12-09"))).andExpect(status().isBadRequest());
        mvc.perform(json(post(requests), "dev-tina-c03", leave("CASUAL", "2026-12-13", "2026-12-13"))).andExpect(status().isBadRequest());
        String first = JsonPath.read(mvc.perform(json(post(requests), "dev-tina-c03", leave("CASUAL", "2026-12-07", "2026-12-09")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.days").value(3.0)).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post(requests), "dev-tina-c03", leave("SICK", "2026-12-09", "2026-12-10"))).andExpect(status().isConflict());
        mvc.perform(as(get("/api/v1/academic-years/" + YEAR + "/leave-balance"), "dev-tina-c03"))
            .andExpect(jsonPath("$[?(@.leaveType=='CASUAL')].pending").value(hasItem(3.0)));

        // Visibility: HR sees all, the member of staff sees their own, a guardian nothing.
        mvc.perform(as(get(requests), "dev-hr-c01")).andExpect(jsonPath("$[*].id").value(hasItem(first)));
        mvc.perform(as(get(requests), "dev-tina-c03")).andExpect(jsonPath("$[*].id").value(hasItem(first)));
        mvc.perform(as(get(requests), "dev-meera")).andExpect(status().isNotFound());

        // Decisions: not by the requester, not by a colleague without the right; HR approves and the balance drops.
        String decide = "/api/v1/leave-requests/" + first + "/decision";
        mvc.perform(json(post(decide), "dev-tina-c03", "{\"decision\":\"APPROVE\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(decide), "dev-meera", "{\"decision\":\"APPROVE\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(decide), "dev-hr-c01", "{\"decision\":\"APPROVE\",\"note\":\"Enjoy\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.decidedBy").value("dev-hr-c01 Staff"));
        mvc.perform(json(post(decide), "dev-hr-c01", "{\"decision\":\"REJECT\",\"note\":\"Changed my mind\"}")).andExpect(status().isConflict());
        mvc.perform(as(get("/api/v1/academic-years/" + YEAR + "/leave-balance"), "dev-tina-c03"))
            .andExpect(jsonPath("$[?(@.leaveType=='CASUAL')].remaining").value(hasItem(1.0)));

        // Only one casual day is left: a two-day request cannot be approved, and rejection needs a reason.
        String second = JsonPath.read(mvc.perform(json(post(requests), "dev-tina-c03", leave("CASUAL", "2026-12-14", "2026-12-15")))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        String d2 = "/api/v1/leave-requests/" + second + "/decision";
        mvc.perform(json(post(d2), "dev-hr-c01", "{\"decision\":\"APPROVE\"}")).andExpect(status().isConflict());
        mvc.perform(json(post(d2), "dev-hr-c01", "{\"decision\":\"REJECT\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(d2), "dev-hr-c01", "{\"decision\":\"REJECT\",\"note\":\"Not enough balance\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));

        // Withdrawing your own pending request; the principal cannot approve their own leave.
        String third = JsonPath.read(mvc.perform(json(post(requests), "dev-tina-c03", leave("UNPAID", "2026-12-21", "2026-12-22")))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post("/api/v1/leave-requests/" + third + "/decision"), "dev-hr-c01", "{\"decision\":\"CANCEL\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/leave-requests/" + third + "/decision"), "dev-tina-c03", "{\"decision\":\"CANCEL\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(json(post("/api/v1/leave-requests/" + third + "/decision"), "dev-tina-c03", "{\"decision\":\"CANCEL\"}")).andExpect(status().isConflict());
        String own = JsonPath.read(mvc.perform(json(post(requests), "dev-prin-c02", leave("UNPAID", "2026-12-28", "2026-12-29")))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post("/api/v1/leave-requests/" + own + "/decision"), "dev-prin-c02", "{\"decision\":\"APPROVE\"}")).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/leave-requests/" + own + "/decision"), "dev-hr-c01", "{\"decision\":\"APPROVE\"}")).andExpect(status().isOk());

        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'hr.leave_approved' and resource_id = '" + first + "'"));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'hr.leave_requested' and resource_id = '" + first + "'"));
    }
}
