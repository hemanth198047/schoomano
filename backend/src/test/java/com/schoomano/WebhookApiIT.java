package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B34: webhooks: who may register an endpoint, which addresses and events are allowed, fan-out, ping and switching off. */
class WebhookApiIT extends ApiITSupport {

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
    void groupAdminRegistersEndpointsAndEventsReachThemOnce() throws Exception {
        staff("d61", "dev-wh-d61", "campus_admin");
        String body = "{\"name\":\"Library sync\",\"url\":\"https://hooks.example.com/schoomano\",\"eventTypes\":[\"config.published\"]}";

        // Only the group administrator manages webhooks.
        mvc.perform(json(post("/api/v1/webhooks"), "dev-wh-d61", body)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/webhooks"), "dev-wh-d61")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/webhooks"), "dev-ravi")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/webhooks/event-types"), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@=='config.published')]").isNotEmpty()).andExpect(jsonPath("$[?(@=='attendance.absent')]").isEmpty());

        // Addresses and events are checked.
        mvc.perform(json(post("/api/v1/webhooks"), "dev-asha", body.replace("https://", "http://"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_url"));
        mvc.perform(json(post("/api/v1/webhooks"), "dev-asha", body.replace("hooks.example.com", "10.0.0.5"))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("bad_url"));
        mvc.perform(json(post("/api/v1/webhooks"), "dev-asha", body.replace("config.published", "attendance.absent"))).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("unknown_event_type"));
        mvc.perform(json(post("/api/v1/webhooks"), "dev-asha", body.replace("[\"config.published\"]", "[]"))).andExpect(status().isBadRequest());

        String made = mvc.perform(json(post("/api/v1/webhooks"), "dev-asha", body)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.endpoint.active").value(true)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(made, "$.endpoint.id");
        String secret = JsonPath.read(made, "$.secret");
        assertTrue(secret.startsWith("whsec_"));
        assertEquals(secret, ownerString("select secret from webhook_endpoint where id = '" + id + "'"));
        mvc.perform(as(get("/api/v1/webhooks"), "dev-asha")).andExpect(jsonPath("$[?(@.id=='" + id + "')].secret").isEmpty())
            .andExpect(jsonPath("$[?(@.id=='" + id + "')].eventTypes[0]").value("config.published"));

        // A matching event becomes one delivery, however often the fan-out runs; other kinds of event do not.
        ownerUpdate("insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload) values ('" + TENANT
            + "', 'config', 'wh-it', 'config.published', '{}'), ('" + TENANT + "', 'import', 'wh-it', 'import.committed', '{}')");
        ownerCount("select webhook_fan_out(1000)");
        ownerCount("select webhook_fan_out(1000)");
        mvc.perform(as(get("/api/v1/webhooks/" + id + "/deliveries"), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].eventType").value("config.published")).andExpect(jsonPath("$[0].status").value("PENDING"));

        // A test event reaches this endpoint whatever it subscribed to; other schools see nothing of it.
        mvc.perform(json(post("/api/v1/webhooks/" + id + "/ping"), "dev-wh-d61", "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/webhooks/" + id + "/ping"), "dev-other", "{}")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/webhooks/" + id + "/deliveries"), "dev-other")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/webhooks/" + id + "/ping"), "dev-asha", "{}")).andExpect(status().isAccepted());
        mvc.perform(as(get("/api/v1/webhooks/" + id + "/deliveries"), "dev-asha")).andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.eventType=='webhook.ping')]").isNotEmpty());

        // Switching off stops new deliveries and pings, once.
        mvc.perform(json(post("/api/v1/webhooks/" + id + "/disable"), "dev-wh-d61", "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/webhooks/" + id + "/disable"), "dev-asha", "{}")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mvc.perform(json(post("/api/v1/webhooks/" + id + "/disable"), "dev-asha", "{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_off"));
        mvc.perform(json(post("/api/v1/webhooks/" + id + "/ping"), "dev-asha", "{}")).andExpect(status().isNotFound());
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'webhook.created' and resource_id = '" + id + "'"));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'webhook.disabled' and resource_id = '" + id + "'"));
    }
}
