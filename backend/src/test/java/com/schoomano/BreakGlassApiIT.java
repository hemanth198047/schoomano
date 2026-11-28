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

/** B44: emergency access to a restricted record is asked for with a reason, works for a short time, and is reviewed by someone else. */
class BreakGlassApiIT extends ApiITSupport {

    static final String ANAYA = "13000000-0000-4000-8000-000000000021";   // not the child HealthApiIT counts audit entries for
    static final String RECORD = "/api/v1/students/" + ANAYA + "/health-record";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private static String request(String reason, int minutes) {
        return "{\"resourceType\":\"health_record\",\"resourceId\":\"" + ANAYA + "\",\"justification\":\"" + reason + "\",\"minutes\":" + minutes + "}";
    }

    private static final String REASON = "IT-BG Child collapsed at dispersal, need the emergency flag";

    @Test
    void emergencyAccessIsGivenForAShortTimeAndReviewedByAnotherPerson() throws Exception {
        try {
            ownerUpdate("delete from break_glass_grant where justification like 'IT-BG%'");
            mvc.perform(as(get(RECORD), "dev-ravi")).andExpect(status().isNotFound());

            // Asking: staff only, a real reason, a short time, a record that exists.
            mvc.perform(json(post("/api/v1/break-glass"), "dev-meera", request(REASON, 30))).andExpect(status().isNotFound());
            mvc.perform(json(post("/api/v1/break-glass"), "dev-ravi", request("urgent", 30))).andExpect(status().isBadRequest());
            mvc.perform(json(post("/api/v1/break-glass"), "dev-ravi", request(REASON, 600))).andExpect(status().isBadRequest());
            mvc.perform(json(post("/api/v1/break-glass"), "dev-ravi", request(REASON, 30).replace(ANAYA, "13000000-0000-4000-8000-0000000000ee")))
                .andExpect(status().isNotFound());
            mvc.perform(json(post("/api/v1/break-glass"), "dev-ravi", request(REASON, 30).replace("health_record", "invoice"))).andExpect(status().isBadRequest());
            String made = mvc.perform(json(post("/api/v1/break-glass"), "dev-ravi", request(REASON, 30))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("ACTIVE")).andReturn().getResponse().getContentAsString();
            String id = JsonPath.read(made, "$.id");
            assertEquals(1, ownerCount("select count(*) from audit_event where action = 'breakglass.granted' and resource_id = '" + id + "'"));

            // Reviewers are told in their inbox, without the child's name or the reason; the person asking is not.
            assertEquals(1, ownerCount("select count(*) from notice where kind = 'SECURITY' and title = 'Emergency access used' and body not like '%IT-BG%' and body like '%opened a health record%'"));
            assertEquals(0, ownerCount("select count(*) from notice_delivery d join notice n on n.id = d.notice_id where n.title = 'Emergency access used' and d.channel = 'IN_APP'"
                + " and d.recipient_person_id = (select person_id from break_glass_grant where id = '" + id + "')"));
            assertEquals(true, ownerCount("select count(*) from notice_delivery d join notice n on n.id = d.notice_id where n.title = 'Emergency access used' and d.channel = 'IN_APP'") >= 1);

            // The grant opens the record for this person (the open is on the record), and not for others.
            mvc.perform(as(get(RECORD), "dev-ravi"));
            assertEquals(1, ownerCount("select count(*) from audit_event where action = 'access.health_record_read' and outcome = 'ALLOW' and resource_id = '" + ANAYA
                + "' and details->>'basis' = 'BREAK_GLASS'"));
            mvc.perform(as(get(RECORD), "dev-farah")).andExpect(status().isNotFound());

            // Review: only people who read the audit trail, never the person it was made to, with a note, once.
            mvc.perform(as(get("/api/v1/break-glass"), "dev-ravi")).andExpect(status().isNotFound());
            mvc.perform(as(get("/api/v1/break-glass"), "dev-asha")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].person").isNotEmpty())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].opened").value(org.hamcrest.Matchers.hasItem(1)))
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].justification").value(org.hamcrest.Matchers.hasItem(REASON)));
            String review = "{\"outcome\":\"APPROPRIATE\",\"note\":\"Matches the dispersal incident report\"}";
            mvc.perform(json(post("/api/v1/break-glass/" + id + "/review"), "dev-ravi", review)).andExpect(status().isNotFound());
            mvc.perform(json(post("/api/v1/break-glass/" + id + "/review"), "dev-asha", review.replace("Matches the dispersal incident report", "ok")))
                .andExpect(status().isBadRequest());
            mvc.perform(json(post("/api/v1/break-glass/" + id + "/review"), "dev-asha", review.replace("APPROPRIATE", "FINE"))).andExpect(status().isBadRequest());
            String own = mvc.perform(json(post("/api/v1/break-glass"), "dev-asha", request("IT-BG Principal checking the allergy flag during an incident", 15)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            mvc.perform(json(post("/api/v1/break-glass/" + JsonPath.read(own, "$.id") + "/review"), "dev-asha", review)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("own_grant"));
            mvc.perform(json(post("/api/v1/break-glass/" + id + "/review"), "dev-asha", review)).andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("APPROPRIATE")).andExpect(jsonPath("$.reviewedBy").isNotEmpty()).andExpect(jsonPath("$.state").value("ACTIVE"));
            mvc.perform(json(post("/api/v1/break-glass/" + id + "/review"), "dev-asha", review)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_reviewed"));
            assertEquals(1, ownerCount("select count(*) from audit_event where action = 'breakglass.reviewed' and resource_id = '" + id + "'"));

            // An inappropriate review ends access at once.
            String second = mvc.perform(json(post("/api/v1/break-glass"), "dev-farah", request("IT-BG Needed to see the allergy list for a trip tomorrow", 60)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
            String secondId = JsonPath.read(second, "$.id");
            mvc.perform(json(post("/api/v1/break-glass/" + secondId + "/review"), "dev-asha",
                "{\"outcome\":\"INAPPROPRIATE\",\"note\":\"No incident that day, trip is not an emergency\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ENDED"));
            assertEquals(1, ownerCount("select count(*) from notice_delivery d join notice n on n.id = d.notice_id where n.title = 'Emergency access ended' and d.channel = 'IN_APP' and n.body not like '%IT-BG%'"
                + " and d.recipient_person_id = (select person_id from break_glass_grant where id = '" + secondId + "')"));
        } finally {
            ownerUpdate("delete from break_glass_grant where justification like 'IT-BG%'");
        }
    }
}
