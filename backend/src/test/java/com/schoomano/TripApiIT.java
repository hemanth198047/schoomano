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

/** B21: trips planned by the campus, consent given by guardians, headcount taken on the day. */
class TripApiIT extends ApiITSupport {

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
    void campusPlansGuardianConsentsStaffCountHeads() throws Exception {
        staff("f01", "dev-tp-f01", "campus_admin");
        staff("f02", "dev-tp-f02", "teacher");
        String list = "/api/v1/academic-years/" + YEAR + "/trips";
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        String body = "{\"name\":\"IT Museum\",\"destination\":\"Science Museum\",\"tripDate\":\"" + today + "\",\"costPaise\":50000,\"consentDeadline\":\"" + today + "\",\"capacity\":5}";

        // Planning is for the campus office.
        mvc.perform(json(post(list), "dev-tp-f02", body)).andExpect(status().isNotFound());
        mvc.perform(json(post(list), "dev-meera", body)).andExpect(status().isNotFound());
        mvc.perform(json(post(list), "dev-tp-f01", body.replace("\"consentDeadline\":\"" + today + "\"", "\"consentDeadline\":\"" + today.plusDays(1) + "\"")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("deadline_after_trip"));
        mvc.perform(json(post(list), "dev-tp-f01", body.replace("\"tripDate\":\"" + today + "\"", "\"tripDate\":\"" + today.minusDays(2) + "\"")
                .replace("\"consentDeadline\":\"" + today + "\"", "\"consentDeadline\":\"" + today.minusDays(3) + "\"")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("trip_in_past"));
        String trip = JsonPath.read(create(list, "dev-tp-f01", body), "$.id");
        mvc.perform(as(get(list), "dev-tp-f02")).andExpect(status().isOk());

        // Only students enrolled this year are invited; the teacher cannot invite.
        String invite = "/api/v1/trips/" + trip + "/participants";
        mvc.perform(json(post(invite), "dev-tp-f02", "{\"studentIds\":[\"" + ANAYA + "\"]}")).andExpect(status().isNotFound());
        mvc.perform(json(post(invite), "dev-tp-f01", "{\"studentIds\":[\"" + YEAR2_ONLY + "\"]}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_student"));

        // Counting heads needs consent first; nothing is recorded for a child nobody consented for.
        String heads = "/api/v1/trips/" + trip + "/headcount";
        String depart = "{\"studentId\":\"" + ANAYA + "\",\"checkpoint\":\"DEPARTED\"}";
        mvc.perform(json(post(invite), "dev-tp-f01", "{\"studentIds\":[\"" + ANAYA + "\"]}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.added").value(1));
        mvc.perform(json(post(invite), "dev-tp-f01", "{\"studentIds\":[\"" + ANAYA + "\"]}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.added").value(0)).andExpect(jsonPath("$.alreadyOnList").value(1));
        mvc.perform(json(post(heads), "dev-tp-f02", depart)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("no_consent"));

        // The guardian decides for their own child; the restricted guardian and other schools cannot.
        String consent = "/api/v1/trips/" + trip + "/consent";
        mvc.perform(json(post(consent), "dev-karan", "{\"studentId\":\"" + ANAYA + "\",\"decision\":\"GIVEN\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(consent), "dev-other", "{\"studentId\":\"" + ANAYA + "\",\"decision\":\"GIVEN\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(consent), "dev-tp-f01", "{\"studentId\":\"" + ANAYA + "\",\"decision\":\"GIVEN\"}")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/trips"), "dev-karan")).andExpect(status().isNotFound());
        mvc.perform(json(post(consent), "dev-meera", "{\"studentId\":\"" + ANAYA + "\",\"decision\":\"MAYBE\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(consent), "dev-meera", "{\"studentId\":\"" + ANAYA + "\",\"decision\":\"DECLINED\",\"note\":\"Unwell\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.replacedEarlier").value(false));
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/trips"), "dev-meera")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("IT Museum")).andExpect(jsonPath("$[0].consent").value("DECLINED"))
            .andExpect(jsonPath("$[0].canDecide").value(true)).andExpect(jsonPath("$[0].costPaise").value(50000));
        mvc.perform(json(post(heads), "dev-tp-f02", depart)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("no_consent"));
        mvc.perform(json(post(consent), "dev-meera", "{\"studentId\":\"" + ANAYA + "\",\"decision\":\"GIVEN\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.replacedEarlier").value(true));
        mvc.perform(as(get(invite), "dev-tp-f02")).andExpect(status().isOk()).andExpect(jsonPath("$[0].consent").value("GIVEN"));
        assertEquals(2, ownerCount("select count(*) from trip_consent where trip_id = '" + trip + "'"));

        // The day: leave, then return; retries are harmless; strangers are refused.
        mvc.perform(json(post(heads), "dev-meera", depart)).andExpect(status().isNotFound());
        mvc.perform(json(post(heads), "dev-tp-f02", depart.replace("DEPARTED", "RETURNED"))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("not_departed"));
        mvc.perform(json(post(heads), "dev-tp-f02", depart)).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(json(post(heads), "dev-tp-f02", depart)).andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(json(post(heads), "dev-tp-f02", depart.replace("DEPARTED", "RETURNED"))).andExpect(status().isOk());
        mvc.perform(as(get(list), "dev-tp-f02")).andExpect(status().isOk()).andExpect(jsonPath("$[0].given").value(1))
            .andExpect(jsonPath("$[0].departed").value(1)).andExpect(jsonPath("$[0].returned").value(1));

        // Closing the trip: teachers cannot; the office can, once.
        mvc.perform(post("/api/v1/trips/" + trip + "/complete").header("Authorization", dev("dev-tp-f02"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/trips/" + trip + "/complete").header("Authorization", dev("dev-tp-f01"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
        mvc.perform(post("/api/v1/trips/" + trip + "/complete").header("Authorization", dev("dev-tp-f01"))).andExpect(status().isConflict());

        // A trip whose deadline has gone cannot take new decisions; a cancelled trip leaves the parent's list.
        String late = JsonPath.read(create(list, "dev-tp-f01", body.replace("IT Museum", "IT Fort").replace("\"tripDate\":\"" + today + "\"", "\"tripDate\":\"" + today.plusDays(10) + "\"")
            .replace("\"consentDeadline\":\"" + today + "\"", "\"consentDeadline\":\"" + today.minusDays(1) + "\"")), "$.id");
        mvc.perform(json(post("/api/v1/trips/" + late + "/participants"), "dev-tp-f01", "{\"studentIds\":[\"" + ANAYA + "\"]}")).andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/trips/" + late + "/consent"), "dev-meera", "{\"studentId\":\"" + ANAYA + "\",\"decision\":\"GIVEN\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("deadline_passed"));
        mvc.perform(post("/api/v1/trips/" + late + "/cancel").header("Authorization", dev("dev-tp-f01"))).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/trips"), "dev-meera")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }
}
