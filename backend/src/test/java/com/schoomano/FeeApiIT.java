package com.schoomano;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.schoomano.platform.PaymentGateway;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B8: invoices, offline payments, online payment through the sandbox gateway, the signed webhook and reconciliation. */
class FeeApiIT extends ApiITSupport {

    static final String NORTH = "12000000-0000-4000-8000-000000000001";
    static final String NORTH_YEAR = "15000000-0000-4000-8000-000000000001";
    static final String CBSE = "14000000-0000-4000-8000-000000000001";
    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String FIONA = "13000000-0000-4000-8000-000000000801";

    @Autowired
    PaymentGateway gateway;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return as(b, who).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    /** Fiona in Grade 5 B, a finance officer, a guardian who may pay and a guardian who may not. */
    private static void fixture() throws Exception {
        ownerUpdate("""
            insert into person (id, tenant_id, school_ref, given_name, family_name) values
              ('13000000-0000-4000-8000-000000000801', '%1$s', 'STU-IT-801', 'Fiona', 'Synthetic') on conflict (id) do nothing;
            insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id, programme_id, class_section_id)
              select '%1$s', '13000000-0000-4000-8000-000000000801', '12000000-0000-4000-8000-000000000001',
                     '15000000-0000-4000-8000-000000000001', '14000000-0000-4000-8000-000000000001', md5('section-CBSE-K12-5-B')::uuid
               where not exists (select 1 from student_enrollment where student_person_id = '13000000-0000-4000-8000-000000000801');
            insert into person (id, tenant_id, school_ref, given_name, family_name) values
              ('13000000-0000-4000-8000-000000000802', '%1$s', 'STF-IT-802', 'Finn', 'Finance') on conflict (id) do nothing;
            insert into identity (tenant_id, person_id, issuer, subject, email)
              values ('%1$s', '13000000-0000-4000-8000-000000000802', 'dev', 'dev-fin802', 'fin802@example.invalid') on conflict do nothing;
            insert into role_grant (tenant_id, person_id, role_key, scope_level, scope_id, reason)
              select '%1$s', '13000000-0000-4000-8000-000000000802', 'finance_officer', 'CAMPUS', '12000000-0000-4000-8000-000000000001', 'it'
               where not exists (select 1 from role_grant where person_id = '13000000-0000-4000-8000-000000000802');
            """.formatted(TENANT));
        String[][] guardians = {{"811", "true"}, {"812", "false"}};
        for (String[] g : guardians) {
            String id = "13000000-0000-4000-8000-000000000" + g[0];
            ownerUpdate("""
                insert into person (id, tenant_id, school_ref, given_name, family_name, mobile_e164)
                  values ('%1$s', '%2$s', 'GRD-IT-%3$s', 'Parent%3$s', 'Synthetic', '+919830000%3$s') on conflict (id) do nothing;
                insert into identity (tenant_id, person_id, issuer, subject, email)
                  values ('%2$s', '%1$s', 'dev', 'dev-g%3$s', 'g%3$s@example.invalid') on conflict do nothing;
                insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type, status,
                                                   can_view_attendance, can_view_academic, can_pay_fees, can_give_consent, can_receive_notices)
                  select '%2$s', '%1$s', '%4$s', 'GUARDIAN', 'ACTIVE', true, true, %5$s, true, true
                   where not exists (select 1 from guardian_relationship where guardian_person_id = '%1$s');
                """.formatted(id, TENANT, g[0], FIONA, g[1]));
        }
    }

    private String issue(String lines) throws Exception {
        String body = "{\"studentId\":\"" + FIONA + "\",\"dueOn\":\"2099-04-30\",\"lines\":" + lines + "}";
        return JsonPath.read(mvc.perform(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/invoices"), "dev-fin802", body))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.invoice.id");
    }

    private void signedEvent(String id, String order, long amount, String outcome, String expected) throws Exception {
        String body = "{\"eventId\":\"" + id + "\",\"orderId\":\"" + order + "\",\"paymentId\":\"pay_" + id + "\",\"amountPaise\":" + amount
            + ",\"status\":\"" + outcome + "\"}";
        mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).header("X-Signature", gateway.sign(body)).content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value(expected));
    }

    @Test
    void financeIssuesAndOnlyFinanceCan() throws Exception {
        fixture();
        String url = "/api/v1/academic-years/" + NORTH_YEAR + "/invoices";
        String body = "{\"studentId\":\"" + FIONA + "\",\"dueOn\":\"2099-04-30\",\"lines\":[{\"description\":\"Tuition\",\"amountPaise\":2500000},"
            + "{\"description\":\"Bus\",\"amountPaise\":500000}]}";
        mvc.perform(json(post(url), "dev-g811", body)).andExpect(status().isNotFound());
        mvc.perform(json(post(url), "dev-fin802", body)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.invoice.totalPaise").value(3000000)).andExpect(jsonPath("$.invoice.status").value("ISSUED"))
            .andExpect(jsonPath("$.lines", hasSize(2)));
        mvc.perform(json(post(url), "dev-fin802", "{\"studentId\":\"" + FIONA + "\",\"dueOn\":\"2099-04-30\",\"lines\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(url), "dev-fin802", "{\"studentId\":\"" + FIONA + "\",\"dueOn\":\"2099-04-30\",\"lines\":[{\"description\":\"Odd\",\"amountPaise\":0}]}"))
            .andExpect(status().isBadRequest());
        mvc.perform(as(get(url), "dev-g811")).andExpect(status().isNotFound());
        mvc.perform(as(get(url + "?studentId=" + FIONA), "dev-fin802")).andExpect(status().isOk());
    }

    @Test
    void bulkIssuingTwiceDoesNotDuplicate() throws Exception {
        fixture();
        String section = ownerString("select md5('section-CBSE-K12-5-B')::uuid::text");
        String url = "/api/v1/academic-years/" + NORTH_YEAR + "/invoices/bulk";
        String body = "{\"sectionId\":\"" + section + "\",\"dueOn\":\"2099-06-30\",\"batchKey\":\"term-1-" + UUID.randomUUID().toString().substring(0, 8)
            + "\",\"lines\":[{\"description\":\"Term 1\",\"amountPaise\":1000000}]}";
        String first = mvc.perform(json(post(url), "dev-fin802", body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        int created = JsonPath.read(first, "$.created");
        mvc.perform(json(post(url), "dev-fin802", body)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.skipped").value(created));
    }

    @Test
    void counterPaymentsAreIdempotentAndNeverOverpay() throws Exception {
        fixture();
        String inv = issue("[{\"description\":\"Tuition\",\"amountPaise\":100000}]");
        String url = "/api/v1/invoices/" + inv + "/payments/offline";
        mvc.perform(json(post(url), "dev-fin802", "{\"amountPaise\":40000,\"method\":\"CASH\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(url), "dev-g811", "{\"amountPaise\":40000,\"method\":\"CASH\"}").header("Idempotency-Key", key())).andExpect(status().isNotFound());
        mvc.perform(json(post(url), "dev-fin802", "{\"amountPaise\":40000,\"method\":\"CHEQUE\"}").header("Idempotency-Key", key())).andExpect(status().isBadRequest());

        String k = key();
        mvc.perform(json(post(url), "dev-fin802", "{\"amountPaise\":40000,\"method\":\"CASH\"}").header("Idempotency-Key", k))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.invoice.status").value("PARTIALLY_PAID")).andExpect(jsonPath("$.invoice.paidPaise").value(40000));
        // The same request again changes nothing.
        mvc.perform(json(post(url), "dev-fin802", "{\"amountPaise\":40000,\"method\":\"CASH\"}").header("Idempotency-Key", k))
            .andExpect(jsonPath("$.invoice.paidPaise").value(40000)).andExpect(jsonPath("$.payments", hasSize(1)));
        // The same key for a different amount is refused.
        mvc.perform(json(post(url), "dev-fin802", "{\"amountPaise\":50000,\"method\":\"CASH\"}").header("Idempotency-Key", k)).andExpect(status().isConflict());
        // More than is due is refused.
        mvc.perform(json(post(url), "dev-fin802", "{\"amountPaise\":60001,\"method\":\"CASH\"}").header("Idempotency-Key", key())).andExpect(status().isConflict());
        mvc.perform(json(post(url), "dev-fin802", "{\"amountPaise\":60000,\"method\":\"UPI_OFFICE\",\"reference\":\"UTR1\"}").header("Idempotency-Key", key()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.invoice.status").value("PAID"));

        // Paid invoices cannot be cancelled.
        mvc.perform(json(post("/api/v1/invoices/" + inv + "/cancel"), "dev-fin802", "{\"reason\":\"Entered by mistake\"}")).andExpect(status().isConflict());
    }

    @Test
    void unpaidInvoiceCanBeCancelledWithAReason() throws Exception {
        fixture();
        String inv = issue("[{\"description\":\"Trip\",\"amountPaise\":50000}]");
        mvc.perform(json(post("/api/v1/invoices/" + inv + "/cancel"), "dev-fin802", "{\"reason\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/invoices/" + inv + "/cancel"), "dev-fin802", "{\"reason\":\"Trip called off\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.invoice.status").value("CANCELLED"));
        mvc.perform(json(post("/api/v1/invoices/" + inv + "/payments/offline"), "dev-fin802", "{\"amountPaise\":100,\"method\":\"CASH\"}")
            .header("Idempotency-Key", key())).andExpect(status().isConflict());
    }

    @Test
    void guardianPaysOnlineAndTheSignedWebhookSettlesItOnce() throws Exception {
        fixture();
        String inv = issue("[{\"description\":\"Tuition\",\"amountPaise\":200000}]");
        String pay = "/api/v1/invoices/" + inv + "/pay";

        // A guardian without the pay-fees capability, and strangers, cannot even see the invoice.
        mvc.perform(as(get("/api/v1/invoices/" + inv), "dev-g812")).andExpect(status().isNotFound());
        mvc.perform(json(post(pay), "dev-g812", "{}").header("Idempotency-Key", key())).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/students/" + FIONA + "/invoices"), "dev-g811")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + inv + "')]", hasSize(1)));
        mvc.perform(json(post(pay), "dev-g811", "{}")).andExpect(status().isBadRequest());

        String k = key();
        String order = JsonPath.read(mvc.perform(json(post(pay), "dev-g811", "{\"amountPaise\":150000}").header("Idempotency-Key", k))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.sandbox").value(true)).andReturn().getResponse().getContentAsString(), "$.orderId");
        // Retrying with the same key gives the same order, not a second one.
        mvc.perform(json(post(pay), "dev-g811", "{\"amountPaise\":150000}").header("Idempotency-Key", k)).andExpect(jsonPath("$.orderId").value(order));
        mvc.perform(json(post(pay), "dev-g811", "{\"amountPaise\":999999999}").header("Idempotency-Key", key())).andExpect(status().isConflict());

        // Nothing is paid until the provider says so.
        mvc.perform(as(get("/api/v1/invoices/" + inv), "dev-g811")).andExpect(jsonPath("$.invoice.paidPaise").value(0));

        // A forged or unsigned webhook is refused.
        String body = "{\"eventId\":\"evt-forged\",\"orderId\":\"" + order + "\",\"amountPaise\":150000,\"status\":\"SUCCEEDED\"}";
        mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).header("X-Signature", "deadbeef").content(body))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/payments/webhook").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());

        // The wrong amount is recorded and not applied; the right one settles; a repeat is recognised.
        signedEvent("evt-" + order + "-a", order, 100, "SUCCEEDED", "AMOUNT_MISMATCH");
        mvc.perform(as(get("/api/v1/invoices/" + inv), "dev-g811")).andExpect(jsonPath("$.invoice.paidPaise").value(0));
        String good = "evt-" + order + "-b";
        signedEvent(good, order, 150000, "SUCCEEDED", "APPLIED");
        signedEvent(good, order, 150000, "SUCCEEDED", "DUPLICATE");
        mvc.perform(as(get("/api/v1/invoices/" + inv), "dev-g811")).andExpect(jsonPath("$.invoice.paidPaise").value(150000))
            .andExpect(jsonPath("$.invoice.status").value("PARTIALLY_PAID"));
        signedEvent("evt-unknown-" + UUID.randomUUID(), "order_nobody", 5, "SUCCEEDED", "UNKNOWN_ORDER");
    }

    @Test
    void sandboxCheckoutGoesThroughTheSameWebhookPathAndReconciliationSeesIt() throws Exception {
        fixture();
        String inv = issue("[{\"description\":\"Lab\",\"amountPaise\":70000}]");
        String order = JsonPath.read(mvc.perform(json(post("/api/v1/invoices/" + inv + "/pay"), "dev-g811", "{}").header("Idempotency-Key", key()))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.amountPaise").value(70000)).andReturn().getResponse().getContentAsString(), "$.orderId");
        // Somebody else cannot complete this payment.
        mvc.perform(json(post("/api/v1/payments/sandbox/complete"), "dev-g812", "{\"orderId\":\"" + order + "\",\"outcome\":\"SUCCEEDED\"}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/payments/sandbox/complete"), "dev-g811", "{\"orderId\":\"" + order + "\",\"outcome\":\"SUCCEEDED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("APPLIED"));
        mvc.perform(as(get("/api/v1/invoices/" + inv), "dev-fin802")).andExpect(jsonPath("$.invoice.status").value("PAID"))
            .andExpect(jsonPath("$.payments[0].method").value("GATEWAY"));

        var res = mvc.perform(as(get("/api/v1/campuses/" + NORTH + "/fees/reconciliation?from=2000-01-01&to=2100-01-01"), "dev-fin802"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long collected = ((Number) JsonPath.read(res, "$.collectedPaise")).longValue();
        assertEquals(true, collected >= 70000, "collected includes the gateway payment");
        mvc.perform(as(get("/api/v1/campuses/" + NORTH + "/fees/reconciliation?from=2000-01-01&to=2100-01-01"), "dev-g811")).andExpect(status().isNotFound());
    }

    @Test
    void failedPaymentLeavesTheInvoiceOpenAndPayableAgain() throws Exception {
        fixture();
        String inv = issue("[{\"description\":\"Fee\",\"amountPaise\":30000}]");
        String order = JsonPath.read(mvc.perform(json(post("/api/v1/invoices/" + inv + "/pay"), "dev-g811", "{}").header("Idempotency-Key", key()))
            .andReturn().getResponse().getContentAsString(), "$.orderId");
        mvc.perform(json(post("/api/v1/payments/sandbox/complete"), "dev-g811", "{\"orderId\":\"" + order + "\",\"outcome\":\"FAILED\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value(is("FAILED_RECORDED")));
        mvc.perform(as(get("/api/v1/invoices/" + inv), "dev-g811")).andExpect(jsonPath("$.invoice.paidPaise").value(0))
            .andExpect(jsonPath("$.invoice.status").value("ISSUED"));
        mvc.perform(json(post("/api/v1/invoices/" + inv + "/pay"), "dev-g811", "{}").header("Idempotency-Key", key())).andExpect(status().isCreated());
    }
}
