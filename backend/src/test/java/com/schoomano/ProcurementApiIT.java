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

/** B17: budgets, purchase requests that someone else approves within the budget, and vendor payments. */
class ProcurementApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";
    static final String YEAR = "15000000-0000-4000-8000-000000000001";

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
    void requestsAreApprovedByAnotherPersonWithinBudgetThenPaid() throws Exception {
        staff("901", "dev-chitra901", "campus_admin");
        staff("902", "dev-prakash902", "principal");
        staff("903", "dev-fin903", "finance_officer");
        String budgets = "/api/v1/academic-years/" + YEAR + "/budgets";
        String requests = "/api/v1/academic-years/" + YEAR + "/purchase-requests";

        // Budgets: finance sets them; a teacher or a guardian cannot, and a budget cannot be set below what is approved.
        String lab = "{\"category\":\"IT Lab\",\"amountPaise\":1000000}";
        mvc.perform(json(put(budgets), "dev-ravi", lab)).andExpect(status().isNotFound());
        mvc.perform(json(put(budgets), "dev-meera", lab)).andExpect(status().isNotFound());
        String labId = JsonPath.read(mvc.perform(json(put(budgets), "dev-fin903", lab)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), "$.id");
        String bigId = JsonPath.read(mvc.perform(json(put(budgets), "dev-fin903", "{\"category\":\"IT Building\",\"amountPaise\":20000000}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(as(get(budgets), "dev-ravi")).andExpect(status().isOk());
        mvc.perform(as(get(budgets), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(get(budgets), "dev-chitra901")).andExpect(status().isOk()).andExpect(jsonPath("$[*].category").value(hasItem("IT Lab")));

        // Vendors: finance adds, teachers can pick; names are unique per campus.
        String vendor = JsonPath.read(create("/api/v1/campuses/" + CAMPUS + "/vendors", "dev-fin903", "{\"name\":\"Lab Supplies IT\",\"contact\":\"9800000000\"}"), "$.id");
        mvc.perform(json(post("/api/v1/campuses/" + CAMPUS + "/vendors"), "dev-fin903", "{\"name\":\"Lab Supplies IT\"}")).andExpect(status().isConflict());
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/vendors"), "dev-ravi")).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].name").value(hasItem("Lab Supplies IT")));

        // Ravi raises a request; he sees it, a guardian cannot, and he cannot approve his own.
        String first = JsonPath.read(create(requests, "dev-ravi", "{\"budgetId\":\"" + labId + "\",\"vendorId\":\"" + vendor
            + "\",\"title\":\"Ten microscopes\",\"amountPaise\":600000}"), "$.id");
        mvc.perform(json(post(requests), "dev-meera", "{\"budgetId\":\"" + labId + "\",\"title\":\"Nope nope\",\"amountPaise\":100}")).andExpect(status().isNotFound());
        mvc.perform(json(post(requests), "dev-ravi", "{\"budgetId\":\"" + labId + "\",\"title\":\"No\",\"amountPaise\":100}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(requests), "dev-ravi", "{\"budgetId\":\"" + labId + "\",\"vendorId\":\"" + labId + "\",\"title\":\"Bad vendor\",\"amountPaise\":100}"))
            .andExpect(status().isBadRequest());
        mvc.perform(as(get(requests), "dev-ravi")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + first + "')].status").value(hasItem("SUBMITTED")));
        mvc.perform(as(get(requests), "dev-meera")).andExpect(status().isNotFound());
        String decide = "/api/v1/purchase-requests/" + first + "/decision";
        mvc.perform(json(post(decide), "dev-ravi", "{\"decision\":\"APPROVE\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(decide), "dev-meera", "{\"decision\":\"APPROVE\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(decide), "dev-fin903", "{\"decision\":\"APPROVE\"}")).andExpect(status().isNotFound());

        // A campus administrator approves; the budget now shows the commitment.
        mvc.perform(json(post(decide), "dev-chitra901", "{\"decision\":\"APPROVE\",\"note\":\"Needed for term 2\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("APPROVED")).andExpect(jsonPath("$.history.length()").value(2));
        mvc.perform(json(post(decide), "dev-chitra901", "{\"decision\":\"APPROVE\"}")).andExpect(status().isConflict());
        mvc.perform(as(get(budgets), "dev-fin903")).andExpect(jsonPath("$[?(@.id=='" + labId + "')].committedPaise").value(hasItem(600000)));
        mvc.perform(json(put(budgets), "dev-fin903", "{\"category\":\"IT Lab\",\"amountPaise\":500000}")).andExpect(status().isConflict());

        // The budget has only 400,000 left: a bigger request cannot be approved, a smaller one can be rejected with a reason.
        String second = JsonPath.read(create(requests, "dev-ravi", "{\"budgetId\":\"" + labId + "\",\"title\":\"Projector\",\"amountPaise\":500000}"), "$.id");
        String d2 = "/api/v1/purchase-requests/" + second + "/decision";
        mvc.perform(json(post(d2), "dev-chitra901", "{\"decision\":\"APPROVE\"}")).andExpect(status().isConflict());
        mvc.perform(json(post(d2), "dev-chitra901", "{\"decision\":\"REJECT\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(d2), "dev-chitra901", "{\"decision\":\"CANCEL\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post(d2), "dev-chitra901", "{\"decision\":\"REJECT\",\"note\":\"Over budget this term\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"));

        // Requesters can withdraw their own pending request.
        String third = JsonPath.read(create(requests, "dev-ravi", "{\"budgetId\":\"" + labId + "\",\"title\":\"Whiteboards\",\"amountPaise\":10000}"), "$.id");
        mvc.perform(json(post("/api/v1/purchase-requests/" + third + "/decision"), "dev-ravi", "{\"decision\":\"CANCEL\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(json(post("/api/v1/purchase-requests/" + third + "/decision"), "dev-ravi", "{\"decision\":\"CANCEL\"}")).andExpect(status().isConflict());

        // Large requests (above 50,000 rupees) need the principal, who cannot approve their own.
        String large = JsonPath.read(create(requests, "dev-ravi", "{\"budgetId\":\"" + bigId + "\",\"title\":\"Server room\",\"amountPaise\":6000000}"), "$.id");
        mvc.perform(as(get(requests), "dev-chitra901")).andExpect(jsonPath("$[?(@.id=='" + large + "')].needsPrincipal").value(hasItem(true)));
        String dl = "/api/v1/purchase-requests/" + large + "/decision";
        mvc.perform(json(post(dl), "dev-chitra901", "{\"decision\":\"APPROVE\"}")).andExpect(status().isForbidden());
        mvc.perform(json(post(dl), "dev-prakash902", "{\"decision\":\"APPROVE\"}")).andExpect(status().isOk());
        String own = JsonPath.read(create(requests, "dev-prakash902", "{\"budgetId\":\"" + bigId + "\",\"title\":\"Principal desk\",\"amountPaise\":100000}"), "$.id");
        mvc.perform(json(post("/api/v1/purchase-requests/" + own + "/decision"), "dev-prakash902", "{\"decision\":\"APPROVE\"}")).andExpect(status().isForbidden());

        // Payment: finance only, approved requests only, never more than approved, once.
        String pay = "/api/v1/purchase-requests/" + first + "/payment";
        String paid = "{\"amountPaise\":600000,\"paidOn\":\"2026-11-20\",\"reference\":\"NEFT-IT-1\"}";
        mvc.perform(json(post(pay), "dev-ravi", paid)).andExpect(status().isNotFound());
        mvc.perform(json(post(pay), "dev-chitra901", paid)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/purchase-requests/" + second + "/payment"), "dev-fin903", paid)).andExpect(status().isConflict());
        mvc.perform(json(post(pay), "dev-fin903", paid.replace("600000", "600001"))).andExpect(status().isBadRequest());
        mvc.perform(json(post(pay), "dev-fin903", paid)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID")).andExpect(jsonPath("$.paidPaise").value(600000))
            .andExpect(jsonPath("$.paymentReference").value("NEFT-IT-1"));
        mvc.perform(json(post(pay), "dev-fin903", paid)).andExpect(status().isConflict());
        mvc.perform(as(get(budgets), "dev-fin903")).andExpect(jsonPath("$[?(@.id=='" + labId + "')].paidPaise").value(hasItem(600000)));

        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'procurement.paid' and resource_id = '" + first + "'"));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'procurement.approved' and resource_id = '" + first + "'"));
    }
}
