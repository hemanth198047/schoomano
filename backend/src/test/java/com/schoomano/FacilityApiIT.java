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

/** B22: rooms booked without clashes by staff, maintenance problems reported and worked through by the campus office. */
class FacilityApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";
    static final String ROOM = "1f000000-0000-4000-8000-0000000000a1";
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

    private static String slot(java.time.OffsetDateTime from, long hours, long length) {
        return "\"startsAt\":\"" + from.plusHours(hours) + "\",\"endsAt\":\"" + from.plusHours(hours + length) + "\"";
    }

    @Test
    void staffBookRoomsWithoutClashesAndCampusWorksThroughProblems() throws Exception {
        staff("a01", "dev-fc-a01", "campus_admin");
        staff("a02", "dev-fc-a02", "teacher");
        staff("a03", "dev-fc-a03", "teacher");
        ownerUpdate("""
            insert into room (id, tenant_id, campus_id, code, name, capacity) values ('%s', '%s', '%s', 'ZZ-IT1', 'IT Hall', 40) on conflict do nothing
            """.formatted(ROOM, TENANT, CAMPUS));
        java.time.OffsetDateTime base = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusDays(3).withMinute(0).withSecond(0).withNano(0);
        String book = "/api/v1/rooms/" + ROOM + "/bookings";

        // Staff book; parents cannot; clashes, the past and day-long bookings are refused.
        mvc.perform(json(post(book), "dev-meera", "{\"purpose\":\"Party\"," + slot(base, 0, 2) + "}")).andExpect(status().isNotFound());
        String first = JsonPath.read(create(book, "dev-fc-a02", "{\"purpose\":\"Assembly rehearsal\"," + slot(base, 0, 2) + "}"), "$.id");
        mvc.perform(json(post(book), "dev-fc-a03", "{\"purpose\":\"Clash\"," + slot(base, 1, 2) + "}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("room_booked"));
        mvc.perform(json(post(book), "dev-fc-a03", "{\"purpose\":\"Past\"," + slot(base, -100, 2) + "}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_time"));
        mvc.perform(json(post(book), "dev-fc-a03", "{\"purpose\":\"Too long\"," + slot(base, 5, 20) + "}")).andExpect(status().isBadRequest());
        String second = JsonPath.read(create(book, "dev-fc-a03", "{\"purpose\":\"Right after\"," + slot(base, 2, 1) + "}"), "$.id");

        // Everyone on the campus sees the bookings and which are theirs.
        String list = "/api/v1/campuses/" + CAMPUS + "/bookings?from=" + base.toLocalDate().minusDays(1) + "&to=" + base.toLocalDate().plusDays(2);
        mvc.perform(as(get(list), "dev-fc-a03")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + first + "')].mine").value(false)).andExpect(jsonPath("$[?(@.id=='" + second + "')].mine").value(true));
        mvc.perform(as(get(list), "dev-meera")).andExpect(status().isNotFound());

        // Teachers cancel their own bookings only; the office can cancel any; a cancelled slot can be booked again.
        mvc.perform(post("/api/v1/bookings/" + first + "/cancel").header("Authorization", dev("dev-fc-a03"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bookings/" + first + "/cancel").header("Authorization", dev("dev-fc-a02"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/v1/bookings/" + first + "/cancel").header("Authorization", dev("dev-fc-a02"))).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/bookings/" + second + "/cancel").header("Authorization", dev("dev-fc-a01"))).andExpect(status().isOk());
        create(book, "dev-fc-a03", "{\"purpose\":\"Reuse\"," + slot(base, 0, 3) + "}");

        // Problems: anyone on staff reports; only the office moves them forward, one step at a time, with a note at the end.
        String issues = "/api/v1/campuses/" + CAMPUS + "/maintenance";
        mvc.perform(json(post(issues), "dev-meera", "{\"title\":\"Broken fan\",\"priority\":\"NORMAL\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(issues), "dev-fc-a02", "{\"title\":\"Broken fan\",\"priority\":\"HIGH\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(issues), "dev-fc-a02", "{\"title\":\"Broken fan\",\"priority\":\"NORMAL\",\"roomId\":\"1f000000-0000-4000-8000-0000000000ff\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_room"));
        String issue = JsonPath.read(create(issues, "dev-fc-a02", "{\"title\":\"Broken fan\",\"priority\":\"URGENT\",\"roomId\":\"" + ROOM + "\"}"), "$.id");
        mvc.perform(as(get(issues + "?status=OPEN"), "dev-fc-a03")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + issue + "')].roomName").value("IT Hall"));
        mvc.perform(post("/api/v1/maintenance/" + issue + "/start").header("Authorization", dev("dev-fc-a02"))).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/maintenance/" + issue + "/resolve"), "dev-fc-a01", "{\"resolution\":\"Replaced\"}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("not_in_progress"));
        mvc.perform(post("/api/v1/maintenance/" + issue + "/start").header("Authorization", dev("dev-fc-a01"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(post("/api/v1/maintenance/" + issue + "/start").header("Authorization", dev("dev-fc-a01"))).andExpect(status().isConflict());
        mvc.perform(json(post("/api/v1/maintenance/" + issue + "/resolve"), "dev-fc-a01", "{\"resolution\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/maintenance/" + issue + "/resolve"), "dev-fc-a01", "{\"resolution\":\"Replaced the capacitor\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RESOLVED")).andExpect(jsonPath("$.resolution").value("Replaced the capacitor"));
        mvc.perform(as(get(issues + "?status=RESOLVED"), "dev-fc-a02")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + issue + "')].status").value("RESOLVED"));
        mvc.perform(as(get(issues + "?status=BROKEN"), "dev-fc-a02")).andExpect(status().isBadRequest());
        mvc.perform(as(get(issues), "dev-other")).andExpect(status().isNotFound());
    }
}
