package com.schoomano;

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

/** B28: the campus visitor log: check-in with a host and badge, check-out once, and who may see it. */
class VisitorApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";

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

    @Test
    void gateRecordsVisitorsAndClosesEachVisitOnce() throws Exception {
        staff("d41", "dev-vs-d41", "campus_admin");
        staff("d42", "dev-vs-d42", "teacher");
        staff("d43", "dev-vs-d43", "principal");
        String host = "13000000-0000-4000-8000-000000000d42";
        String visits = "/api/v1/campuses/" + CAMPUS + "/visits";
        String body = "{\"visitorName\":\"IT Visitor\",\"organisation\":\"Books Ltd\",\"phone\":\"9800000001\",\"purpose\":\"Book fair\",\"badgeNo\":\"IT-7\"}";

        // Only the office records; teachers and parents cannot see or record; the principal can look.
        mvc.perform(json(post(visits), "dev-vs-d42", body)).andExpect(status().isNotFound());
        mvc.perform(json(post(visits), "dev-meera", body)).andExpect(status().isNotFound());
        mvc.perform(json(post(visits), "dev-vs-d43", body)).andExpect(status().isNotFound());
        mvc.perform(as(get(visits), "dev-vs-d42")).andExpect(status().isNotFound());
        mvc.perform(as(get(visits), "dev-vs-d43")).andExpect(status().isOk());

        // A host must be staff of the campus.
        mvc.perform(json(post(visits), "dev-vs-d41", body.replace("}", ",\"hostPersonId\":\"" + host + "\"}")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_host"));
        mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + host), "dev-vs-d41",
            "{\"employeeNo\":\"E-IT-D42\",\"designation\":\"Teacher\",\"employmentType\":\"FULL_TIME\",\"joinedOn\":\"2020-04-01\"}")).andExpect(status().isOk());
        mvc.perform(json(post(visits), "dev-vs-d41", body.replace("Book fair", "x"))).andExpect(status().isBadRequest());
        mvc.perform(json(post(visits), "dev-vs-d41", body.replace("9800000001", "abc"))).andExpect(status().isBadRequest());
        String created = mvc.perform(json(post(visits), "dev-vs-d41", body.replace("}", ",\"hostPersonId\":\"" + host + "\"}")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.inside").value(true)).andExpect(jsonPath("$.host").value("dev-vs-d42 Staff"))
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        assertEquals(1, ownerCount("select count(*) from notice_delivery d join notice n on n.id = d.notice_id where n.kind = 'VISITOR' and d.channel = 'IN_APP'"
            + " and n.title = 'Visitor here to see you: IT Visitor' and d.recipient_person_id = '" + host + "'"));
        mvc.perform(as(get("/api/v1/notices/mine"), "dev-vs-d42")).andExpect(jsonPath("$[?(@.title=='Visitor here to see you: IT Visitor')].kind").value("VISITOR"));

        // The badge stays with whoever is inside; check-out frees it and happens once.
        mvc.perform(json(post(visits), "dev-vs-d41", body.replace("IT Visitor", "Another"))).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("badge_in_use"));
        mvc.perform(as(get(visits), "dev-vs-d43")).andExpect(jsonPath("$[?(@.id=='" + id + "')].visitorName").value("IT Visitor"));
        mvc.perform(json(post("/api/v1/visits/" + id + "/checkout"), "dev-vs-d42", "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/visits/" + id + "/checkout"), "dev-vs-d41", "{}")).andExpect(status().isOk()).andExpect(jsonPath("$.inside").value(false));
        mvc.perform(json(post("/api/v1/visits/" + id + "/checkout"), "dev-vs-d41", "{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_out"));
        mvc.perform(as(get(visits), "dev-vs-d43")).andExpect(jsonPath("$[?(@.id=='" + id + "')]").isEmpty());
        mvc.perform(as(get(visits + "?view=today"), "dev-vs-d43")).andExpect(jsonPath("$[?(@.id=='" + id + "')].inside").value(false));
        mvc.perform(as(get(visits + "?view=week"), "dev-vs-d43")).andExpect(status().isBadRequest());
        mvc.perform(json(post(visits), "dev-vs-d41", body.replace("IT Visitor", "Another"))).andExpect(status().isCreated());
        assertEquals(0, ownerCount("select count(*) from notice where kind = 'VISITOR' and title = 'Visitor here to see you: Another'"));

        // Other schools see nothing.
        mvc.perform(as(get(visits), "dev-other")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/visits/" + id + "/checkout"), "dev-other", "{}")).andExpect(status().isNotFound());
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'visitor.checked_out' and resource_id = '" + id + "'"));
    }

    @Test
    void frontDeskWorksTheGateWithoutSeeingStaffRecordsOrStudents() throws Exception {
        staff("d41", "dev-vs-d41", "campus_admin");
        staff("d44", "dev-vs-d44", "front_desk");
        staff("d45", "dev-vs-d45", "teacher");
        String host = "13000000-0000-4000-8000-000000000d45";
        mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + host), "dev-vs-d41",
            "{\"employeeNo\":\"E-IT-D45\",\"designation\":\"Teacher\",\"employmentType\":\"FULL_TIME\",\"joinedOn\":\"2020-04-01\"}")).andExpect(status().isOk());
        String visits = "/api/v1/campuses/" + CAMPUS + "/visits";

        // The desk lists possible hosts by name only, checks visitors in and out, and sees nothing else.
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/visit-hosts"), "dev-vs-d44")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.personId=='" + host + "')].designation").value("Teacher"));
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/visit-hosts"), "dev-vs-d45")).andExpect(status().isNotFound());
        String id = JsonPath.read(mvc.perform(json(post(visits), "dev-vs-d44",
            "{\"visitorName\":\"IT Desk Visitor\",\"purpose\":\"Interview\",\"hostPersonId\":\"" + host + "\"}")).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(as(get(visits), "dev-vs-d44")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + id + "')].purpose").value("Interview"));
        mvc.perform(json(post("/api/v1/visits/" + id + "/checkout"), "dev-vs-d44", "{}")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/staff"), "dev-vs-d44")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/bookings"), "dev-vs-d44")).andExpect(status().isNotFound());
    }

    @Test
    void staffAnnounceGuestsAndTheGateChecksThemIn() throws Exception {
        staff("d46", "dev-vs-d46", "campus_admin");
        staff("d47", "dev-vs-d47", "teacher");
        staff("d48", "dev-vs-d48", "front_desk");
        String d47 = "13000000-0000-4000-8000-000000000d47";
        String d46 = "13000000-0000-4000-8000-000000000d46";
        for (String[] p : new String[][] {{d47, "E-IT-D47"}, {d46, "E-IT-D46"}}) {
            mvc.perform(json(put("/api/v1/campuses/" + CAMPUS + "/staff/" + p[0]), "dev-vs-d46",
                "{\"employeeNo\":\"" + p[1] + "\",\"designation\":\"Teacher\",\"employmentType\":\"FULL_TIME\",\"joinedOn\":\"2020-04-01\"}")).andExpect(status().isOk());
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        String expected = "/api/v1/campuses/" + CAMPUS + "/expected-visitors";
        String body = "{\"visitorName\":\"IT Guest\",\"organisation\":\"Books Ltd\",\"purpose\":\"Demo\",\"expectedOn\":\"" + today + "\"}";

        // Staff announce; parents cannot; dates are limited; the gate sees today's guests.
        mvc.perform(json(post(expected), "dev-meera", body)).andExpect(status().isNotFound());
        mvc.perform(json(post(expected), "dev-vs-d47", body.replace(today.toString(), today.minusDays(1).toString()))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_date"));
        mvc.perform(json(post(expected), "dev-vs-d47", body.replace(today.toString(), today.plusDays(40).toString()))).andExpect(status().isBadRequest());
        String id = JsonPath.read(mvc.perform(json(post(expected), "dev-vs-d47", body)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.host").value("dev-vs-d47 Staff")).andReturn().getResponse().getContentAsString(), "$.id");
        String later = JsonPath.read(mvc.perform(json(post(expected), "dev-vs-d47", body.replace("IT Guest", "IT Later").replace(today.toString(), today.plusDays(3).toString())))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(as(get(expected + "?view=mine"), "dev-vs-d47")).andExpect(jsonPath("$[?(@.id=='" + id + "')].visitorName").value("IT Guest"));
        mvc.perform(as(get(expected + "?view=mine"), "dev-vs-d46")).andExpect(jsonPath("$[?(@.id=='" + id + "')]").isEmpty());
        mvc.perform(as(get(expected + "?view=today"), "dev-vs-d47")).andExpect(status().isNotFound());
        mvc.perform(as(get(expected + "?view=today"), "dev-vs-d48")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + id + "')].visitorName").value("IT Guest")).andExpect(jsonPath("$[?(@.id=='" + later + "')]").isEmpty());

        // Only the host cancels; the gate cannot check in a guest expected on another day.
        mvc.perform(json(post("/api/v1/expected-visitors/" + later + "/cancel"), "dev-vs-d46", "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/expected-visitors/" + later + "/arrive"), "dev-vs-d48", "{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_today"));
        mvc.perform(json(post("/api/v1/expected-visitors/" + later + "/cancel"), "dev-vs-d47", "{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(json(post("/api/v1/expected-visitors/" + later + "/cancel"), "dev-vs-d47", "{}")).andExpect(status().isConflict());

        // Arrival creates the visit with the host, tells the host, and closes the announcement once.
        mvc.perform(json(post("/api/v1/expected-visitors/" + id + "/arrive"), "dev-vs-d47", "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/expected-visitors/" + id + "/arrive"), "dev-vs-d48", "{\"badgeNo\":\"IT-9\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.inside").value(true)).andExpect(jsonPath("$.host").value("dev-vs-d47 Staff")).andExpect(jsonPath("$.badgeNo").value("IT-9"));
        mvc.perform(json(post("/api/v1/expected-visitors/" + id + "/arrive"), "dev-vs-d48", "{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_closed"));
        assertEquals(1, ownerCount("select count(*) from notice_delivery d join notice n on n.id = d.notice_id where n.kind = 'VISITOR' and d.channel = 'IN_APP'"
            + " and n.title = 'Visitor here to see you: IT Guest' and d.recipient_person_id = '" + d47 + "'"));
        mvc.perform(as(get(expected + "?view=today"), "dev-vs-d48")).andExpect(jsonPath("$[?(@.id=='" + id + "')]").isEmpty());
        mvc.perform(as(get(expected + "?view=week"), "dev-vs-d48")).andExpect(status().isBadRequest());
        mvc.perform(as(get(expected + "?view=mine"), "dev-other")).andExpect(status().isNotFound());
    }
}
