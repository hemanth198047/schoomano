package com.schoomano;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** Stage A acceptance through the real HTTP stack, real PostgreSQL (RLS on) and the unprivileged app role. */
class StageAApiIT extends ApiITSupport {

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyResponseCarriesACorrelationId() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void guardianWithTwoChildrenOnTwoCampusesSeesExactlyThoseChildren() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", dev("dev-meera")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.children", hasSize(2)))
            .andExpect(jsonPath("$.children[*].campusName", containsInAnyOrder("North Campus", "South Campus")));
    }

    @Test
    void separatedGuardianSeesNoticeCapabilityOnly() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", dev("dev-karan")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.children", hasSize(1)))
            .andExpect(jsonPath("$.children[0].capabilities", containsInAnyOrder("RECEIVE_NOTICES")));
    }

    @Test
    void otherTenantNeverSeesTenantACampuses() throws Exception {
        mvc.perform(get("/api/v1/campuses").header("Authorization", dev("dev-other")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].code", not(hasItem("BLR-N"))))
            .andExpect(jsonPath("$[*].code", not(hasItem("BLR-S"))));
    }

    @Test
    void clientCannotSelectATenantItDoesNotBelongTo() throws Exception {
        mvc.perform(get("/api/v1/campuses").header("Authorization", dev("dev-other"))
                .header("X-Schoomano-Tenant", "demo-group"))
            .andExpect(status().isConflict());
    }

    @Test
    void guardianSeesNoCampusDirectory() throws Exception {
        mvc.perform(get("/api/v1/campuses").header("Authorization", dev("dev-meera")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void teacherCannotDraftConfiguration() throws Exception {
        mvc.perform(post("/api/v1/config/attendance.late_after_minutes/drafts")
                .header("Authorization", dev("dev-ravi")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"scopeLevel":"GROUP","scopeId":"10000000-0000-4000-8000-000000000001","value":1,
                     "effectiveFrom":"2027-01-01T00:00:00+05:30","changeNote":"attempt"}"""))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminDraftsPreviewsAndPublishesWithoutRewritingHistory() throws Exception {
        String created = mvc.perform(post("/api/v1/config/attendance.late_after_minutes/drafts")
                .header("Authorization", dev("dev-asha")).contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"scopeLevel":"GROUP","scopeId":"10000000-0000-4000-8000-000000000001","value":5,
                     "effectiveFrom":"2026-12-01T00:00:00+05:30","changeNote":"Stricter from term 3"}"""))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        // South keeps its campus override; North changes.
        mvc.perform(post("/api/v1/config/drafts/" + id + "/preview").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.campusName=='North Campus')].changed").value(true))
            .andExpect(jsonPath("$[?(@.campusName=='South Campus')].changed").value(false));

        mvc.perform(post("/api/v1/config/drafts/" + id + "/publish").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"));

        String north = "12000000-0000-4000-8000-000000000001";
        mvc.perform(get("/api/v1/config/attendance.late_after_minutes").header("Authorization", dev("dev-asha"))
                .param("campusId", north).param("asOf", "2026-09-01T09:00:00+05:30"))
            .andExpect(jsonPath("$.value").value(10));
        mvc.perform(get("/api/v1/config/attendance.late_after_minutes").header("Authorization", dev("dev-asha"))
                .param("campusId", north).param("asOf", "2026-12-15T09:00:00+05:30"))
            .andExpect(jsonPath("$.value").value(5));

        // Publishing the same draft twice is refused.
        mvc.perform(post("/api/v1/config/drafts/" + id + "/publish").header("Authorization", dev("dev-asha")))
            .andExpect(status().isConflict());
    }
}
