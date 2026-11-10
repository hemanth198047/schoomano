package com.schoomano;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B3 acceptance: student profiles, guardian relationships with capabilities, separated-guardian restriction. */
class StudentApiIT extends ApiITSupport {

    static final String NORTH_YEAR = "15000000-0000-4000-8000-000000000001";
    static final String ANAYA = "13000000-0000-4000-8000-000000000020";
    static final String VIVAAN = "13000000-0000-4000-8000-000000000021";

    private ResultActions as(String who, MockHttpServletRequestBuilder b) throws Exception {
        return mvc.perform(b.header("Authorization", dev(who)));
    }

    private ResultActions json(String who, MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String newStudent(String given) throws Exception {
        return JsonPath.read(json("dev-asha", post("/api/v1/academic-years/" + NORTH_YEAR + "/students"),
            "{\"givenName\":\"" + given + "\",\"familyName\":\"Test\"}").andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void staffSeeGuardiansWithCapabilitiesAndHouseholds() throws Exception {
        as("dev-asha", get("/api/v1/students/" + ANAYA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.view").value("STAFF"))
            .andExpect(jsonPath("$.guardians", hasSize(2)))
            .andExpect(jsonPath("$.guardians[?(@.givenName=='Meera')].mobile").value(hasItem("+91 98000 00010")))
            .andExpect(jsonPath("$.guardians[?(@.givenName=='Meera')].sharesHousehold").value(hasItem(true)))
            .andExpect(jsonPath("$.guardians[?(@.givenName=='Karan')].status").value(hasItem("RESTRICTED")))
            .andExpect(jsonPath("$.guardians[?(@.givenName=='Karan')].sharesHousehold").value(hasItem(false)))
            .andExpect(jsonPath("$.guardians[?(@.givenName=='Karan')].capabilities[*]").value(containsInAnyOrder("RECEIVE_NOTICES")));
    }

    @Test
    void separatedGuardianSeesOnlyTheChildsNameAndNeverTheOtherParent() throws Exception {
        // Karan (restricted, notices only): name only; no placement, no birth date, no guardians.
        as("dev-karan", get("/api/v1/students/" + ANAYA))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.view").value("GUARDIAN"))
            .andExpect(jsonPath("$.givenName").value("Anaya"))
            .andExpect(jsonPath("$.placements", hasSize(0)))
            .andExpect(jsonPath("$.dateOfBirth").doesNotExist())
            .andExpect(jsonPath("$.guardians", hasSize(0)))
            .andExpect(jsonPath("$.yourCapabilities[*]").value(containsInAnyOrder("RECEIVE_NOTICES")));
        // Meera (full guardian): her child's placement, still not the other guardian's details.
        as("dev-meera", get("/api/v1/students/" + ANAYA))
            .andExpect(jsonPath("$.placements[0].gradeName").value("Grade 5"))
            .andExpect(jsonPath("$.guardians", hasSize(0)));
        // Neither may open a child they are not linked to, nor list students.
        as("dev-karan", get("/api/v1/students/" + VIVAAN)).andExpect(status().isNotFound());
        as("dev-meera", get("/api/v1/academic-years/" + NORTH_YEAR + "/students")).andExpect(status().isNotFound());
        as("dev-other", get("/api/v1/students/" + ANAYA)).andExpect(status().isNotFound());
    }

    @Test
    void teachersReadTheirCampusOnlyAndCannotEdit() throws Exception {
        as("dev-ravi", get("/api/v1/academic-years/" + NORTH_YEAR + "/students").param("q", "anaya"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.students[0].displayName").value("Anaya Student (synthetic)"));
        as("dev-ravi", get("/api/v1/students/" + VIVAAN)).andExpect(status().isNotFound());   // South campus
        json("dev-ravi", patch("/api/v1/students/" + ANAYA), "{\"preferredName\":\"Annu\"}").andExpect(status().isForbidden());
    }

    @Test
    void oldSystemFieldsAreReadOnlyDuringCoexistence() throws Exception {
        String id = newStudent("Locked");
        ownerUpdate("update person set provenance = '{\"source\":\"LEGACY\",\"ownedFields\":[\"givenName\",\"familyName\","
            + "\"dateOfBirth\",\"placement\"]}' where id = '" + id + "'");
        json("dev-asha", patch("/api/v1/students/" + id), "{\"familyName\":\"Changed\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("owned_by_old_system"));
        json("dev-asha", patch("/api/v1/students/" + id), "{\"preferredName\":\"Lucky\",\"reason\":\"Goes by Lucky\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.preferredName").value("Lucky"))
            .andExpect(jsonPath("$.ownedFields", hasSize(4)));
    }

    @Test
    void guardiansAreAddedOncePerMobileAndRelationshipsChangeOnlyWithAReason() throws Exception {
        String a = newStudent("Riya");
        String b = newStudent("Rohan");
        String add = """
            {"givenName":"Sunita","familyName":"Test","mobile":"098450 12345","relationshipType":"MOTHER","status":"ACTIVE",
             "capabilities":["VIEW_ATTENDANCE","VIEW_ACADEMIC","PAY_FEES","GIVE_CONSENT","RECEIVE_NOTICES","PICKUP"],
             "reason":"Mother at admission"}""";
        String profile = json("dev-asha", post("/api/v1/students/" + a + "/guardians"), add)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.guardians[0].mobile").value("+91 98450 12345"))
            .andExpect(jsonPath("$.guardians[0].sharesHousehold").value(true))
            .andReturn().getResponse().getContentAsString();
        String rel = JsonPath.read(profile, "$.guardians[0].relationshipId");
        String guardian = JsonPath.read(profile, "$.guardians[0].guardianId");
        // The same mobile on a sibling links the same person, never a duplicate.
        json("dev-asha", post("/api/v1/students/" + b + "/guardians"), add.replace("098450 12345", "+91 98450-12345"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.guardians[0].guardianId").value(guardian));
        assertEquals(1, ownerCount("select count(*) from person where mobile_e164 = '+919845012345'"));
        json("dev-asha", post("/api/v1/students/" + a + "/guardians"), add).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("already_linked"));
        json("dev-asha", post("/api/v1/students/" + a + "/guardians"), add.replace("098450 12345", "080 2345"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_mobile"));

        // Restricting: pickup must be confirmed explicitly, a reason is required, and the household is no longer shared.
        json("dev-asha", patch("/api/v1/guardian-relationships/" + rel),
            "{\"status\":\"RESTRICTED\",\"capabilities\":[\"RECEIVE_NOTICES\",\"PICKUP\"],\"reason\":\"Separation agreement\"}")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.problems[0]").value(startsWith("A restricted guardian normally cannot collect")));
        json("dev-asha", patch("/api/v1/guardian-relationships/" + rel),
            "{\"status\":\"RESTRICTED\",\"capabilities\":[\"RECEIVE_NOTICES\"],\"reason\":\"short\"}")
            .andExpect(status().isConflict());
        json("dev-asha", patch("/api/v1/guardian-relationships/" + rel),
            "{\"status\":\"RESTRICTED\",\"capabilities\":[\"RECEIVE_NOTICES\"],\"reason\":\"Separation agreement on file\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.guardians[0].status").value("RESTRICTED"))
            .andExpect(jsonPath("$.guardians[0].sharesHousehold").value(false));
        assertEquals("Separation agreement on file", ownerString(
            "select reason from audit_event where action = 'guardian.relationship_changed' and resource_id = '" + rel + "'"));
        json("dev-ravi", patch("/api/v1/guardian-relationships/" + rel),
            "{\"status\":\"ACTIVE\",\"capabilities\":[\"RECEIVE_NOTICES\"],\"reason\":\"Teacher attempt here\"}")
            .andExpect(status().isForbidden());

        // Ending is final; a mobile cannot move to someone who already has it.
        json("dev-asha", patch("/api/v1/guardian-relationships/" + rel),
            "{\"status\":\"ENDED\",\"capabilities\":[],\"reason\":\"Guardianship transferred\"}").andExpect(status().isOk());
        json("dev-asha", patch("/api/v1/guardian-relationships/" + rel),
            "{\"status\":\"ACTIVE\",\"capabilities\":[\"RECEIVE_NOTICES\"],\"reason\":\"Reopen attempt here\"}")
            .andExpect(status().isConflict());
        json("dev-asha", patch("/api/v1/guardians/" + guardian),
            "{\"mobile\":\"98000 00010\",\"reason\":\"Parent gave new number\"}")
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("mobile_in_use"));
    }
}
