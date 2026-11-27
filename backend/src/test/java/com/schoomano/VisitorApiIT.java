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

        // Other schools see nothing.
        mvc.perform(as(get(visits), "dev-other")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/visits/" + id + "/checkout"), "dev-other", "{}")).andExpect(status().isNotFound());
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'visitor.checked_out' and resource_id = '" + id + "'"));
    }
}
