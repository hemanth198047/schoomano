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

    @Test
    void purchaseRequestsCanBeApprovedThroughAChain() throws Exception {
        staff("d21", "dev-wf-d21", "teacher");
        staff("d22", "dev-wf-d22", "principal");
        staff("d23", "dev-wf-d23", "finance_officer");
        String chains = "/api/v1/campuses/" + CAMPUS + "/workflow-chains";
        String chainId = JsonPath.read(create(chains, "dev-asha", "{\"chainKey\":\"it-buy-one\",\"name\":\"IT Buy one step\",\"steps\":[{\"roleKey\":\"principal\",\"label\":\"Principal\"}]}"), "$.id");
        mvc.perform(json(post("/api/v1/workflow-chains/" + chainId + "/publish"), "dev-asha", "{}")).andExpect(status().isOk());
        String year = "15000000-0000-4000-8000-000000000001";
        String budget = JsonPath.read(mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/academic-years/" + year + "/budgets"),
            "dev-wf-d23", "{\"category\":\"IT Chain budget\",\"amountPaise\":100000}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        String requests = "/api/v1/academic-years/" + year + "/purchase-requests";

        // A made-up chain is refused; a real one holds the request until the principal decides in the chain.
        mvc.perform(json(post(requests), "dev-wf-d21", "{\"budgetId\":\"" + budget + "\",\"title\":\"Bad chain\",\"amountPaise\":100,\"chainId\":\"" + budget + "\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_chain"));
        String body = "{\"budgetId\":\"" + budget + "\",\"title\":\"Projector via chain\",\"amountPaise\":60000,\"chainId\":\"" + chainId + "\"}";
        String created = create(requests, "dev-wf-d21", body);
        String purchase = JsonPath.read(created, "$.id");
        String wf = JsonPath.read(created, "$.workflowRequestId");
        assertEquals("Principal", JsonPath.read(created, "$.waitingFor"));
        mvc.perform(json(post("/api/v1/purchase-requests/" + purchase + "/decision"), "dev-wf-d22", "{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("use_approvals"));
        mvc.perform(json(post("/api/v1/workflow-requests/" + wf + "/decision"), "dev-wf-d21", "{\"decision\":\"APPROVED\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/workflow-requests/" + wf + "/decision"), "dev-wf-d22", "{\"decision\":\"APPROVED\"}")).andExpect(status().isOk());
        mvc.perform(as(get(requests), "dev-wf-d21")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + purchase + "')].status").value("APPROVED"));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'procurement.approved' and resource_id = '" + purchase + "'"));

        // A rejection in the chain rejects the purchase; the requester cancelling withdraws the chain request.
        String second = create(requests, "dev-wf-d21", body.replace("Projector", "Screens"));
        mvc.perform(json(post("/api/v1/workflow-requests/" + JsonPath.read(second, "$.workflowRequestId") + "/decision"), "dev-wf-d22",
            "{\"decision\":\"REJECTED\",\"note\":\"Not now\"}")).andExpect(status().isOk());
        mvc.perform(as(get(requests), "dev-wf-d21")).andExpect(jsonPath("$[?(@.title=='Screens via chain')].status").value("REJECTED"));
        String third = create(requests, "dev-wf-d21", body.replace("Projector", "Chairs").replace("60000", "1000"));
        mvc.perform(json(post("/api/v1/purchase-requests/" + JsonPath.read(third, "$.id") + "/decision"), "dev-wf-d21", "{\"decision\":\"CANCEL\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertEquals("WITHDRAWN", ownerString("select status from workflow_request where id = '" + JsonPath.read(third, "$.workflowRequestId") + "'"));

        // The budget still has to have room when the chain approves.
        String fourth = create(requests, "dev-wf-d21", body.replace("Projector", "Cabinets").replace("60000", "50000"));
        mvc.perform(json(post("/api/v1/workflow-requests/" + JsonPath.read(fourth, "$.workflowRequestId") + "/decision"), "dev-wf-d22", "{\"decision\":\"APPROVED\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("over_budget"));
        assertEquals("PENDING", ownerString("select status from workflow_request where id = '" + JsonPath.read(fourth, "$.workflowRequestId") + "'"));
    }

    @Test
    void leaveCanBeApprovedThroughAChain() throws Exception {
        staff("d31", "dev-wf-d31", "teacher");
        staff("d32", "dev-wf-d32", "principal");
        staff("d33", "dev-wf-d33", "campus_admin");
        String d31 = "13000000-0000-4000-8000-000000000d31";
        String chains = "/api/v1/campuses/" + CAMPUS + "/workflow-chains";
        String chainId = JsonPath.read(create(chains, "dev-asha", "{\"chainKey\":\"it-leave-one\",\"name\":\"IT Leave one step\",\"steps\":[{\"roleKey\":\"principal\",\"label\":\"Principal\"}]}"), "$.id");
        mvc.perform(json(post("/api/v1/workflow-chains/" + chainId + "/publish"), "dev-asha", "{}")).andExpect(status().isOk());
        String year = "15000000-0000-4000-8000-000000000001";
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/campuses/" + CAMPUS + "/staff/" + d31), "dev-wf-d33",
            "{\"employeeNo\":\"E-IT-D31\",\"designation\":\"Teacher\",\"employmentType\":\"FULL_TIME\",\"joinedOn\":\"2020-04-01\"}")).andExpect(status().isOk());
        mvc.perform(json(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/academic-years/" + year + "/leave-allowances"), "dev-wf-d33",
            "{\"leaveType\":\"EARNED\",\"days\":3}")).andExpect(status().isOk());
        String leaves = "/api/v1/academic-years/" + year + "/leave-requests";
        java.util.function.BiFunction<String, String, String> body = (from, to) ->
            "{\"leaveType\":\"EARNED\",\"fromDate\":\"" + from + "\",\"toDate\":\"" + to + "\",\"chainId\":\"" + chainId + "\"}";

        mvc.perform(json(post(leaves), "dev-wf-d31", body.apply("2026-12-14", "2026-12-15").replace(chainId, "13000000-0000-4000-8000-000000000d31")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_chain"));
        String first = create(leaves, "dev-wf-d31", body.apply("2026-12-14", "2026-12-15"));
        String leave1 = JsonPath.read(first, "$.id");
        String wf1 = JsonPath.read(first, "$.workflowRequestId");
        assertEquals("Principal", JsonPath.read(first, "$.waitingFor"));
        mvc.perform(json(post("/api/v1/leave-requests/" + leave1 + "/decision"), "dev-wf-d32", "{\"decision\":\"APPROVE\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("use_approvals"));
        mvc.perform(json(post("/api/v1/workflow-requests/" + wf1 + "/decision"), "dev-wf-d31", "{\"decision\":\"APPROVED\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/workflow-requests/" + wf1 + "/decision"), "dev-wf-d32", "{\"decision\":\"APPROVED\"}")).andExpect(status().isOk());
        mvc.perform(as(get(leaves), "dev-wf-d31")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + leave1 + "')].status").value("APPROVED"));

        // The balance is still checked when the chain approves: 2 used of 3, another 2 days does not fit.
        String second = create(leaves, "dev-wf-d31", body.apply("2026-12-16", "2026-12-17"));
        String wf2 = JsonPath.read(second, "$.workflowRequestId");
        mvc.perform(json(post("/api/v1/workflow-requests/" + wf2 + "/decision"), "dev-wf-d32", "{\"decision\":\"APPROVED\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("insufficient_balance"));
        assertEquals("PENDING", ownerString("select status from workflow_request where id = '" + wf2 + "'"));

        // A rejection in the chain rejects the leave with its reason; cancelling withdraws the chain request.
        String third = create(leaves, "dev-wf-d31", body.apply("2026-12-21", "2026-12-21"));
        mvc.perform(json(post("/api/v1/workflow-requests/" + JsonPath.read(third, "$.workflowRequestId") + "/decision"), "dev-wf-d32",
            "{\"decision\":\"REJECTED\",\"note\":\"Exams that day\"}")).andExpect(status().isOk());
        mvc.perform(as(get(leaves), "dev-wf-d31")).andExpect(jsonPath("$[?(@.id=='" + JsonPath.read(third, "$.id") + "')].status").value("REJECTED"))
            .andExpect(jsonPath("$[?(@.id=='" + JsonPath.read(third, "$.id") + "')].decisionNote").value("Exams that day"));
        mvc.perform(json(post("/api/v1/leave-requests/" + JsonPath.read(second, "$.id") + "/decision"), "dev-wf-d31", "{\"decision\":\"CANCEL\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertEquals("WITHDRAWN", ownerString("select status from workflow_request where id = '" + wf2 + "'"));
    }
}
