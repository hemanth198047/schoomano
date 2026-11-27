package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B33: API keys for outside systems: shown once, read-only, one campus, expiry and instant revoke. */
class ApiKeyApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String auth, String body) {
        return b.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String auth) {
        return b.header("Authorization", auth);
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
    void keyReadsOwnCampusOnlyAndStopsWhenRevokedOrExpired() throws Exception {
        staff("d51", "dev-ak-d51", "campus_admin");
        staff("d52", "dev-ak-d52", "teacher");
        String admin = dev("dev-ak-d51");
        String keys = "/api/v1/campuses/" + CAMPUS + "/api-keys";

        // Only campus administration manages keys.
        mvc.perform(json(post(keys), dev("dev-ak-d52"), "{\"name\":\"Library sync\"}")).andExpect(status().isNotFound());
        mvc.perform(as(get(keys), dev("dev-ak-d52"))).andExpect(status().isNotFound());
        mvc.perform(as(get(keys), dev("dev-other"))).andExpect(status().isNotFound());
        mvc.perform(json(post(keys), admin, "{\"name\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(keys), admin, "{\"name\":\"Library sync\",\"validDays\":900}")).andExpect(status().isBadRequest());

        String made = mvc.perform(json(post(keys), admin, "{\"name\":\"Library sync\",\"validDays\":30}")).andExpect(status().isCreated())
            .andExpect(jsonPath("$.key.status").value("ACTIVE")).andReturn().getResponse().getContentAsString();
        String secret = JsonPath.read(made, "$.secret");
        String id = JsonPath.read(made, "$.key.id");
        assertTrue(secret.startsWith("smk_"));
        assertEquals(0, ownerCount("select count(*) from api_key where token_hash = convert_to('" + secret + "', 'UTF8')"));
        mvc.perform(as(get(keys), admin)).andExpect(jsonPath("$[?(@.id=='" + id + "')].hint").value(secret.substring(secret.length() - 4)))
            .andExpect(jsonPath("$[?(@.id=='" + id + "')].secret").isEmpty());

        // The key reads the campus and nothing more.
        String bearer = "Bearer " + secret;
        mvc.perform(as(get("/api/v1/campuses"), bearer)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(CAMPUS));
        mvc.perform(as(get(keys), bearer)).andExpect(status().isNotFound());
        mvc.perform(json(post(keys), bearer, "{\"name\":\"Sneaky key\"}")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/fee-heads"), bearer)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/campuses/" + CAMPUS + "/visits"), bearer)).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/campuses"), "Bearer smk_" + "A".repeat(43))).andExpect(status().isUnauthorized());

        // Expiry ends it, revoke ends it at once and only once.
        String other = JsonPath.read(mvc.perform(json(post(keys), admin, "{\"name\":\"Canteen\"}")).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString(), "$.secret");
        mvc.perform(as(get("/api/v1/campuses"), "Bearer " + other)).andExpect(status().isOk());
        mvc.perform(json(post("/api/v1/api-keys/" + id + "/revoke"), dev("dev-ak-d52"), "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/api-keys/" + id + "/revoke"), dev("dev-other"), "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/api-keys/" + id + "/revoke"), admin, "{}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        mvc.perform(json(post("/api/v1/api-keys/" + id + "/revoke"), admin, "{}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_revoked"));
        mvc.perform(as(get("/api/v1/campuses"), bearer)).andExpect(status().isUnauthorized());
        mvc.perform(as(get("/api/v1/campuses"), "Bearer " + other)).andExpect(status().isOk());

        ownerUpdate("update api_key set expires_at = now() - interval '1 minute', created_at = now() - interval '2 days' where token_hint is not null and name = 'Canteen'");
        mvc.perform(as(get("/api/v1/campuses"), "Bearer " + other)).andExpect(status().isUnauthorized());
        assertFalse(ownerCount("select count(*) from audit_event where action = 'apikey.created' and resource_id = '" + id + "'") == 0);
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'apikey.revoked' and resource_id = '" + id + "'"));
    }
}
