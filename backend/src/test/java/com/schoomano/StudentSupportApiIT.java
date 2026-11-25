package com.schoomano;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B14: confidential support cases. Teachers and guardians (even of the same child) never see them. */
class StudentSupportApiIT extends ApiITSupport {

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    @Test
    void confidentialCasesWithNotesReviewAndClosure() throws Exception {
        String anaya = "13000000-0000-4000-8000-000000000020";
        String base = "/api/v1/students/" + anaya + "/support-cases";
        String body = "{\"category\":\"WELLBEING\",\"title\":\"Settling in after the move\",\"firstNote\":\"Parent asked for a check-in\",\"reviewOn\":\"2020-01-01\"}";

        // Only the principal and administrators can open one; teachers and the child's own guardian see nothing.
        mvc.perform(json(post(base), "dev-ravi", body)).andExpect(status().isNotFound());
        mvc.perform(json(post(base), "dev-meera", body)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/students/13000000-0000-4000-8000-000000000002/support-cases"), "dev-asha", body)).andExpect(status().isNotFound());
        String id = JsonPath.read(mvc.perform(json(post(base), "dev-asha", body)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.reviewDue").value(true)).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(as(get(base), "dev-ravi")).andExpect(status().isNotFound());
        mvc.perform(as(get(base), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/support-cases/" + id), "dev-ravi")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/support-cases/" + id), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/support-cases/" + id), "dev-other")).andExpect(status().isNotFound());
        mvc.perform(as(get(base), "dev-asha")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));

        // Notes add up and are attributed; reading the case is audited.
        mvc.perform(json(post("/api/v1/support-cases/" + id + "/notes"), "dev-asha", "{\"body\":\"Spoke with the class teacher\"}")).andExpect(status().isCreated());
        mvc.perform(json(post("/api/v1/support-cases/" + id + "/notes"), "dev-ravi", "{\"body\":\"Teacher note\"}")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/support-cases/" + id), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$.notes", hasSize(2))).andExpect(jsonPath("$.notes[1].author").value("Asha Admin (synthetic)"));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'support_case.viewed' and resource_id = '" + id + "'"));

        // Monitoring with a later review date, then closing needs a reason and is final.
        mvc.perform(json(post("/api/v1/support-cases/" + id + "/status"), "dev-asha", "{\"status\":\"MONITORING\",\"reviewOn\":\"2999-01-01\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MONITORING")).andExpect(jsonPath("$.reviewDue").value(false));
        mvc.perform(json(post("/api/v1/support-cases/" + id + "/status"), "dev-asha", "{\"status\":\"CLOSED\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/support-cases/" + id + "/status"), "dev-asha", "{\"status\":\"CLOSED\",\"reason\":\"Settled well, no further concerns\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        mvc.perform(json(post("/api/v1/support-cases/" + id + "/notes"), "dev-asha", "{\"body\":\"Too late\"}")).andExpect(status().isConflict());
        mvc.perform(json(post("/api/v1/support-cases/" + id + "/status"), "dev-asha", "{\"status\":\"OPEN\"}")).andExpect(status().isConflict());
        mvc.perform(as(get("/api/v1/support-cases/" + id), "dev-asha")).andExpect(jsonPath("$.closedReason").value("Settled well, no further concerns"));

        // The year overview lists it, filtered by status.
        mvc.perform(as(get("/api/v1/academic-years/15000000-0000-4000-8000-000000000001/support-cases?status=CLOSED"), "dev-asha"))
            .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + id + "')]", hasSize(1)));
        mvc.perform(as(get("/api/v1/academic-years/15000000-0000-4000-8000-000000000001/support-cases"), "dev-ravi")).andExpect(status().isNotFound());
    }
}
