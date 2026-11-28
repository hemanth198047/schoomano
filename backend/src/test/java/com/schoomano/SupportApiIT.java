package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B10: school-approved, time-limited, revocable support access; the audit trail shows it; health at a glance. */
class SupportApiIT extends ApiITSupport {

    static final String SCHOOL = "demo-group";
    static final String NORTH = "12000000-0000-4000-8000-000000000001";

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    /** Support names the school it wants to enter. */
    private static MockHttpServletRequestBuilder asSupport(MockHttpServletRequestBuilder b, String who) {
        return as(b, who).header("X-Schoomano-Tenant", SCHOOL);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return as(b, who).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void supportEntersOnlyThroughAGrantTheSchoolApprovedAndCanBeSentAwayAtAnyTime() throws Exception {
        String helper = "dev-helper-a";
        String ref = "dev:" + helper;

        // No grant: support is not a member of any school.
        mvc.perform(asSupport(get("/api/v1/operations/health"), helper)).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("no_membership"));

        // Only the school's administrator can approve; a campus administrator cannot.
        String body = "{\"supportUserRef\":\"" + ref + "\",\"purpose\":\"Look into the stuck fee import\",\"hours\":2}";
        mvc.perform(json(post("/api/v1/support/grants"), "dev-ravi", body)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/support/grants"), "dev-asha", body.replace("\"hours\":2", "\"hours\":9"))).andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/support/grants"), "dev-asha", body.replace("Look into the stuck fee import", "help"))).andExpect(status().isBadRequest());
        String id = JsonPath.read(mvc.perform(json(post("/api/v1/support/grants"), "dev-asha", body)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("ACTIVE")).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(as(get("/api/v1/support/grants"), "dev-ravi")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/support/grants"), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + id + "')].supportUserRef").value(hasItem(ref)));

        // The first use tells the school's principals and group administrators once, however much support then does.
        String toldSql = "select count(*) from notice where kind = 'SECURITY' and title = 'Support has signed in' and body like '" + ref + " from platform support%'";
        long told = ownerCount(toldSql);
        // With the grant support can see operational health and the audit trail, and it says it is support.
        mvc.perform(asSupport(get("/api/v1/operations/health"), helper)).andExpect(status().isOk())
            .andExpect(jsonPath("$.supportAccessLive").value(greaterThan(0))).andExpect(jsonPath("$.checks", hasSize(7)));
        mvc.perform(asSupport(get("/api/v1/me"), helper)).andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Platform support")).andExpect(jsonPath("$.roles", hasSize(0)));
        mvc.perform(asSupport(get("/api/v1/audit?limit=5"), helper)).andExpect(status().isOk());
        assertEquals(told + 1, ownerCount(toldSql));
        assertEquals(1, ownerCount("select count(*) from notice_delivery d join notice n on n.id = d.notice_id where n.title = 'Support has signed in' and n.body like '"
            + ref + " from platform support%' and d.channel = 'IN_APP' and d.recipient_person_id = '13000000-0000-4000-8000-000000000001'"), "the group administrator has it in the inbox");
        // The grant does not reach other schools, and does not work without naming one.
        mvc.perform(as(get("/api/v1/operations/health"), helper)).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/operations/health"), helper).header("X-Schoomano-Tenant", "other-group")).andExpect(status().isForbidden());

        // Support cannot reach children's records, money, or change anything.
        mvc.perform(asSupport(get("/api/v1/campuses/" + NORTH + "/fee-heads"), helper)).andExpect(status().isNotFound());
        mvc.perform(asSupport(get("/api/v1/campuses/" + NORTH + "/fees/reconciliation?from=2026-01-01&to=2026-12-31"), helper)).andExpect(status().isNotFound());
        mvc.perform(asSupport(get("/api/v1/support/grants"), helper)).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/support/grants"), helper, body).header("X-Schoomano-Tenant", SCHOOL)).andExpect(status().isNotFound());

        // The school sees what support did.
        mvc.perform(as(get("/api/v1/audit?actorKind=SUPPORT"), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].actorName").value("Support: " + ref))
            .andExpect(jsonPath("$[?(@.action=='access.audit_read')]").exists());
        mvc.perform(as(get("/api/v1/audit?actorKind=USER&action=support."), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.action=='support.grant_approved')]").exists());

        // Ending the access works at once, and a second revoke is refused.
        mvc.perform(json(post("/api/v1/support/grants/" + id + "/revoke"), "dev-ravi", "{}")).andExpect(status().isNotFound());
        mvc.perform(json(post("/api/v1/support/grants/" + id + "/revoke"), "dev-asha", "{\"reason\":\"Finished\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("REVOKED"));
        mvc.perform(asSupport(get("/api/v1/operations/health"), helper)).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/v1/support/grants/" + id + "/revoke"), "dev-asha", "{}")).andExpect(status().isConflict());
    }

    @Test
    void auditTrailIsReadableOnlyByThoseWhoMayAndVerifiesIntact() throws Exception {
        mvc.perform(as(get("/api/v1/audit"), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/audit/verify"), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/operations/health"), "dev-meera")).andExpect(status().isNotFound());
        // Every read of the trail is itself recorded, so a few reads guarantee there is something to page through.
        for (int i = 0; i < 4; i++) {
            mvc.perform(as(get("/api/v1/operations/health"), "dev-asha")).andExpect(status().isOk());
        }
        mvc.perform(as(get("/api/v1/audit?limit=3"), "dev-asha")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[0].seq").value(greaterThan(0)));
        long first = ((Number) JsonPath.read(mvc.perform(as(get("/api/v1/audit?limit=2"), "dev-asha")).andReturn().getResponse().getContentAsString(), "$[1].seq")).longValue();
        mvc.perform(as(get("/api/v1/audit?limit=2&beforeSeq=" + first), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].seq").value(not(first)));
        mvc.perform(as(get("/api/v1/audit/verify"), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$.intact").value(true)).andExpect(jsonPath("$.checked").value(greaterThan(0)));
    }
}
