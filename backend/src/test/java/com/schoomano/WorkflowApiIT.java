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

/** B25: approval chains designed by the campus, published as versions, and requests that walk them step by step. */
class WorkflowApiIT extends ApiITSupport {

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

    private String create(String url, String who, String body) throws Exception {
        return mvc.perform(json(post(url), who, body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void chainsAreVersionedAndRequestsWalkThemOneStepAtATime() throws Exception {
        staff("d11", "dev-wf-g01", "campus_admin");
        staff("d12", "dev-wf-g02", "teacher");
        staff("d13", "dev-wf-g03", "principal");
        staff("d14", "dev-wf-g04", "finance_officer");
        String chains = "/api/v1/campuses/" + CAMPUS + "/workflow-chains";
        String requests = "/api/v1/campuses/" + CAMPUS + "/workflow-requests";
        String chain = "{\"chainKey\":\"it-trip-spend\",\"name\":\"IT Trip spend\",\"steps\":[{\"roleKey\":\"principal\",\"label\":\"Principal\"},{\"roleKey\":\"finance_officer\",\"label\":\"Finance\"}]}";

        // Only campus leaders design chains; parents and teachers do not.
        mvc.perform(json(post(chains), "dev-wf-g02", chain)).andExpect(status().isNotFound());
        mvc.perform(json(post(chains), "dev-meera", chain)).andExpect(status().isNotFound());
        mvc.perform(json(post(chains), "dev-wf-g01", chain.replace("it-trip-spend", "X"))).andExpect(status().isBadRequest());
        String draft = create(chains, "dev-wf-g01", chain);
        String chainId = JsonPath.read(draft, "$.id");
        assertEquals("DRAFT", JsonPath.read(draft, "$.status"));
        assertEquals(2, ((java.util.List<?>) JsonPath.read(draft, "$.steps")).size());

        // A draft cannot be used; a teacher sees only published chains.
        String body = "{\"chainId\":\"" + chainId + "\",\"subject\":\"IT Museum trip bus\",\"details\":\"Two buses\"}";
        mvc.perform(json(post(requests), "dev-wf-g02", body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_chain"));
        mvc.perform(json(post("/api/v1/workflow-chains/" + chainId + "/publish"), "dev-wf-g02", "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/workflow-chains/" + chainId + "/publish"), "dev-wf-g01", "{}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"));
        mvc.perform(as(get(chains), "dev-wf-g02")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + chainId + "')].status").value("PUBLISHED"));
        mvc.perform(json(post("/api/v1/workflow-chains/" + chainId + "/publish"), "dev-wf-g01", "{}")).andExpect(status().isConflict());

        // Submit, then only the principal may decide at step 1, never the requester.
        mvc.perform(json(post(requests), "dev-meera", body)).andExpect(status().isNotFound());
        String reqId = JsonPath.read(create(requests, "dev-wf-g02", body), "$.id");
        String decide = "/api/v1/workflow-requests/" + reqId + "/decision";
        mvc.perform(as(get(requests + "?view=mine"), "dev-wf-g02")).andExpect(jsonPath("$[?(@.id=='" + reqId + "')].waitingFor").value("Principal"));
        mvc.perform(as(get(requests + "?view=inbox"), "dev-wf-g03")).andExpect(jsonPath("$[?(@.id=='" + reqId + "')].subject").value("IT Museum trip bus"));
        mvc.perform(as(get(requests + "?view=inbox"), "dev-wf-g04")).andExpect(jsonPath("$[?(@.id=='" + reqId + "')]").isEmpty());
        mvc.perform(json(post(decide), "dev-wf-g02", "{\"decision\":\"APPROVED\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(decide), "dev-wf-g04", "{\"decision\":\"APPROVED\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(decide), "dev-wf-g03", "{\"decision\":\"MAYBE\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(decide), "dev-wf-g03", "{\"decision\":\"APPROVED\",\"note\":\"Fine\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.currentStep").value(2)).andExpect(jsonPath("$.waitingFor").value("Finance"));
        mvc.perform(json(post(decide), "dev-wf-g03", "{\"decision\":\"APPROVED\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(decide), "dev-wf-g04", "{\"decision\":\"APPROVED\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.closedAt").exists());
        mvc.perform(json(post(decide), "dev-wf-g04", "{\"decision\":\"APPROVED\"}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("request_closed"));
        mvc.perform(as(get("/api/v1/workflow-requests/" + reqId), "dev-wf-g02")).andExpect(status().isOk()).andExpect(jsonPath("$.steps[0].decision").value("APPROVED"));
        mvc.perform(as(get("/api/v1/workflow-requests/" + reqId), "dev-meera")).andExpect(status().isNotFound());

        // A rejection needs a reason and ends the request.
        String second = JsonPath.read(create(requests, "dev-wf-g02", body.replace("bus", "tickets")), "$.id");
        String decide2 = "/api/v1/workflow-requests/" + second + "/decision";
        mvc.perform(json(post(decide2), "dev-wf-g03", "{\"decision\":\"REJECTED\"}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("note_required"));
        mvc.perform(json(post(decide2), "dev-wf-g03", "{\"decision\":\"REJECTED\",\"note\":\"Over budget\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));

        // The requester may withdraw only their own open request.
        String third = JsonPath.read(create(requests, "dev-wf-g02", body.replace("bus", "lunch")), "$.id");
        mvc.perform(json(post("/api/v1/workflow-requests/" + third + "/withdraw"), "dev-wf-g03", "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/workflow-requests/" + third + "/withdraw"), "dev-wf-g02", "{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WITHDRAWN"));
        mvc.perform(json(post("/api/v1/workflow-requests/" + third + "/withdraw"), "dev-wf-g02", "{}")).andExpect(status().isConflict());

        // Only leaders see everything; a new version retires the old one and old requests keep their steps.
        mvc.perform(as(get(requests + "?view=all"), "dev-wf-g02")).andExpect(status().isNotFound());
        mvc.perform(as(get(requests + "?view=all"), "dev-wf-g01")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + reqId + "')].status").value("APPROVED"));
        String v2 = create(chains, "dev-wf-g01", chain.replace("IT Trip spend", "IT Trip spend v2"));
        assertEquals(2, (int) JsonPath.read(v2, "$.version"));
        mvc.perform(json(post("/api/v1/workflow-chains/" + JsonPath.read(v2, "$.id") + "/publish"), "dev-wf-g01", "{}")).andExpect(status().isOk());
        mvc.perform(as(get(chains), "dev-wf-g01")).andExpect(jsonPath("$[?(@.id=='" + chainId + "')].status").value("RETIRED"));
        mvc.perform(json(post(requests), "dev-wf-g02", body)).andExpect(status().isBadRequest());

        // Other tenants see nothing.
        mvc.perform(as(get(chains), "dev-other")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/workflow-requests/" + reqId), "dev-other")).andExpect(status().isNotFound());
    }
}
