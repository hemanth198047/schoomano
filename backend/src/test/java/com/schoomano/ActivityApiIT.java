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

/** B20: activities and seats run by the campus, attendance taken by the leader, guardians see their own child. */
class ActivityApiIT extends ApiITSupport {

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
    void campusRunsActivitiesLeaderTakesAttendanceGuardianSeesOwnChild() throws Exception {
        staff("e01", "dev-ac-e01", "campus_admin");
        staff("e02", "dev-ac-e02", "teacher");
        String list = "/api/v1/academic-years/" + YEAR + "/activities";

        // Running activities is for the campus office; a teacher can look but not create.
        mvc.perform(json(post(list), "dev-ac-e02", "{\"name\":\"IT Chess\",\"kind\":\"CLUB\",\"capacity\":1}")).andExpect(status().isNotFound());
        mvc.perform(json(post(list), "dev-meera", "{\"name\":\"IT Chess\",\"kind\":\"CLUB\",\"capacity\":1}")).andExpect(status().isNotFound());
        mvc.perform(json(post(list), "dev-ac-e01", "{\"name\":\"IT Chess\",\"kind\":\"BAND\",\"capacity\":1}")).andExpect(status().isBadRequest());
        String activity = JsonPath.read(create(list, "dev-ac-e01", "{\"name\":\"IT Chess\",\"kind\":\"CLUB\",\"capacity\":1,\"leadName\":\"Mr Rao\"}"), "$.id");
        mvc.perform(json(post(list), "dev-ac-e01", "{\"name\":\"IT Chess\",\"kind\":\"CLUB\"}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("duplicate_activity"));
        mvc.perform(as(get(list), "dev-ac-e02")).andExpect(status().isOk());

        // Only students enrolled that year join, and the seat limit holds.
        String members = "/api/v1/activities/" + activity + "/members";
        mvc.perform(json(post(members), "dev-ac-e02", "{\"studentId\":\"" + ANAYA + "\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(members), "dev-ac-e01", "{\"studentId\":\"" + YEAR2_ONLY + "\"}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_member"));
        String member = JsonPath.read(create(members, "dev-ac-e01", "{\"studentId\":\"" + ANAYA + "\"}"), "$.id");
        mvc.perform(json(post(members), "dev-ac-e01", "{\"studentId\":\"" + ANAYA + "\"}")).andExpect(status().isConflict());
        mvc.perform(as(get(members), "dev-ac-e02")).andExpect(status().isOk()).andExpect(jsonPath("$[0].studentId").value(ANAYA));

        // The leader opens a session and marks who came; a retry is harmless; strangers and the future are refused.
        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        String sessions = "/api/v1/activities/" + activity + "/sessions";
        mvc.perform(json(post(sessions), "dev-meera", "{\"sessionDate\":\"" + today + "\"}")).andExpect(status().isNotFound());
        String session = JsonPath.read(create(sessions, "dev-ac-e02", "{\"sessionDate\":\"" + today + "\",\"topic\":\"Openings\"}"), "$.id");
        mvc.perform(json(post(sessions), "dev-ac-e02", "{\"sessionDate\":\"" + today + "\"}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("duplicate_session"));
        String marks = "/api/v1/activity-sessions/" + session + "/attendance";
        mvc.perform(json(post(marks), "dev-meera", "{\"marks\":[{\"studentId\":\"" + ANAYA + "\",\"present\":true}]}")).andExpect(status().isNotFound());
        mvc.perform(json(post(marks), "dev-ac-e02", "{\"marks\":[{\"studentId\":\"" + YEAR2_ONLY + "\",\"present\":true}]}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("not_a_member"));
        mvc.perform(json(post(marks), "dev-ac-e02", "{\"marks\":[{\"studentId\":\"" + ANAYA + "\",\"present\":true}]}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.recorded").value(1)).andExpect(jsonPath("$.alreadyRecorded").value(0));
        mvc.perform(json(post(marks), "dev-ac-e02", "{\"marks\":[{\"studentId\":\"" + ANAYA + "\",\"present\":false}]}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.recorded").value(0)).andExpect(jsonPath("$.alreadyRecorded").value(1));
        mvc.perform(as(get(marks), "dev-ac-e02")).andExpect(status().isOk()).andExpect(jsonPath("$[0].present").value(true));
        mvc.perform(as(get(sessions), "dev-ac-e02")).andExpect(status().isOk()).andExpect(jsonPath("$[0].present").value(1));
        String future = JsonPath.read(create(sessions, "dev-ac-e02", "{\"sessionDate\":\"2999-01-01\"}"), "$.id");
        mvc.perform(json(post("/api/v1/activity-sessions/" + future + "/attendance"), "dev-ac-e02",
            "{\"marks\":[{\"studentId\":\"" + ANAYA + "\",\"present\":true}]}")).andExpect(status().isBadRequest());
        assertEquals(1, ownerCount("select count(*) from activity_attendance where student_person_id = '" + ANAYA + "' and session_id = '" + session + "'"));

        // Guardians see their own child's activities; the restricted guardian and other schools do not.
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/activities"), "dev-meera")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").value("IT Chess")).andExpect(jsonPath("$[0].sessions").value(1)).andExpect(jsonPath("$[0].attended").value(1));
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/activities"), "dev-karan")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/activities"), "dev-other")).andExpect(status().isNotFound());

        // Withdrawing frees the seat; closing stops further joins and sessions.
        mvc.perform(post("/api/v1/activity-members/" + member + "/withdraw").header("Authorization", dev("dev-ac-e02"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/activity-members/" + member + "/withdraw").header("Authorization", dev("dev-ac-e01"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/activity-members/" + member + "/withdraw").header("Authorization", dev("dev-ac-e01"))).andExpect(status().isConflict());
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/activities"), "dev-meera")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/v1/activities/" + activity + "/close").header("Authorization", dev("dev-ac-e02"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/activities/" + activity + "/close").header("Authorization", dev("dev-ac-e01"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(post("/api/v1/activities/" + activity + "/close").header("Authorization", dev("dev-ac-e01"))).andExpect(status().isConflict());
        mvc.perform(json(post(members), "dev-ac-e01", "{\"studentId\":\"" + ANAYA + "\"}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("activity_closed"));
    }
}
