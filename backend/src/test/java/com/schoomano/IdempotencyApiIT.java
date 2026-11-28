package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B36: a repeated write with the same Idempotency-Key returns the first answer and does the work once. */
class IdempotencyApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";
    static final String KEYS = "/api/v1/campuses/" + CAMPUS + "/api-keys";

    private static MockHttpServletRequestBuilder send(String who, String idemKey, String body) {
        MockHttpServletRequestBuilder b = post(KEYS).header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
        return idemKey == null ? b : b.header("Idempotency-Key", idemKey);
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
    void repeatedWritesDoTheWorkOnce() throws Exception {
        staff("d81", "dev-id-d81", "campus_admin");
        String body = "{\"name\":\"IT idem key\",\"validDays\":30}";

        // The same key and request twice: one key is made, the second answer is the first one again.
        String first = mvc.perform(send("dev-id-d81", "idem-it-key-0001", body)).andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replay")).andReturn().getResponse().getContentAsString();
        String second = mvc.perform(send("dev-id-d81", "idem-it-key-0001", body)).andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replay", "true")).andReturn().getResponse().getContentAsString();
        assertEquals((String) JsonPath.read(first, "$.secret"), JsonPath.read(second, "$.secret"));
        assertEquals((String) JsonPath.read(first, "$.key.id"), JsonPath.read(second, "$.key.id"));
        assertEquals(1, ownerCount("select count(*) from api_key where name = 'IT idem key'"));

        // The same key on a different request is refused; nothing is made.
        mvc.perform(send("dev-id-d81", "idem-it-key-0001", body.replace("30", "60"))).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        assertEquals(1, ownerCount("select count(*) from api_key where name = 'IT idem key'"));

        // Another caller's use of the same key is separate.
        mvc.perform(send("dev-asha", "idem-it-key-0001", "{\"name\":\"IT idem key asha\"}")).andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replay"));

        // A failed request is forgotten, so the client can fix it and retry with the same key.
        mvc.perform(send("dev-id-d81", "idem-it-key-0002", "{\"name\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(send("dev-id-d81", "idem-it-key-0002", "{\"name\":\"IT idem fixed\"}")).andExpect(status().isCreated())
            .andExpect(header().doesNotExist("Idempotent-Replay"));
        mvc.perform(send("dev-id-d81", "idem-it-key-0002", "{\"name\":\"IT idem fixed\"}")).andExpect(status().isCreated())
            .andExpect(header().string("Idempotent-Replay", "true"));
        assertEquals(1, ownerCount("select count(*) from api_key where name = 'IT idem fixed'"));

        // A badly formed key is refused, a request without a key is not touched, and a stranger gets nothing.
        mvc.perform(send("dev-id-d81", "short", body)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_idempotency_key"));
        mvc.perform(send("dev-id-d81", null, "{\"name\":\"IT idem plain\"}")).andExpect(status().isCreated());
        mvc.perform(send("dev-id-d81", null, "{\"name\":\"IT idem plain\"}")).andExpect(status().isCreated());
        assertEquals(2, ownerCount("select count(*) from api_key where name = 'IT idem plain'"));
        mvc.perform(post(KEYS).header("Idempotency-Key", "idem-it-key-0003").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
    }
}
