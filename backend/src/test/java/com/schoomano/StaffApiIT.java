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

/** B61: group administrators add staff and give or end their roles; nobody else can, and the school cannot lock itself out. */
class StaffApiIT extends ApiITSupport {

    static final String CAMPUS = "12000000-0000-4000-8000-000000000001";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void aGroupAdministratorAddsStaffAndGivesAndEndsRoles() throws Exception {
        String email = "it.staff.b61@example.org";
        String id = null;
        try {
            mvc.perform(get("/api/v1/staff").header("Authorization", dev("dev-ravi"))).andExpect(status().isNotFound());
            mvc.perform(json(post("/api/v1/staff"), "dev-ravi", "{\"givenName\":\"Nope\",\"email\":\"nope@example.org\"}")).andExpect(status().isNotFound());

            String made = mvc.perform(json(post("/api/v1/staff"), "dev-asha", "{\"givenName\":\"Meena\",\"familyName\":\"Iyer\",\"email\":\"" + email.toUpperCase() + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.displayName").value("Meena Iyer")).andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.roles.length()").value(0)).andReturn().getResponse().getContentAsString();
            id = JsonPath.read(made, "$.personId");
            mvc.perform(json(post("/api/v1/staff"), "dev-asha", "{\"givenName\":\"Meena\",\"email\":\"" + email + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_listed"));

            String base = "/api/v1/staff/" + id + "/roles";
            // A reason is needed; unknown roles, the service role, a campus that is not the school's and a campus role for the whole group are refused.
            mvc.perform(json(post(base), "dev-asha", "{\"roleKey\":\"teacher\",\"scopeLevel\":\"CAMPUS\",\"scopeId\":\"" + CAMPUS + "\",\"reason\":\"short\"}")).andExpect(status().isBadRequest());
            mvc.perform(json(post(base), "dev-asha", "{\"roleKey\":\"wizard\",\"scopeLevel\":\"GROUP\",\"reason\":\"Joined as the new wizard\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unknown_role"));
            mvc.perform(json(post(base), "dev-asha", "{\"roleKey\":\"integration_reader\",\"scopeLevel\":\"GROUP\",\"reason\":\"Should not be possible\"}")).andExpect(status().isBadRequest());
            mvc.perform(json(post(base), "dev-asha", "{\"roleKey\":\"teacher\",\"scopeLevel\":\"CAMPUS\",\"scopeId\":\"12000000-0000-4000-8000-0000000000ff\",\"reason\":\"Campus is not ours\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("unknown_campus"));
            mvc.perform(json(post(base), "dev-asha", "{\"roleKey\":\"teacher\",\"scopeLevel\":\"GROUP\",\"reason\":\"A teacher of everything\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("campus_role"));
            mvc.perform(json(post(base), "dev-asha", "{\"roleKey\":\"group_admin\",\"scopeLevel\":\"CAMPUS\",\"scopeId\":\"" + CAMPUS + "\",\"reason\":\"Admin of one campus?\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("group_role"));

            String granted = mvc.perform(json(post(base), "dev-asha", "{\"roleKey\":\"teacher\",\"scopeLevel\":\"CAMPUS\",\"scopeId\":\"" + CAMPUS + "\",\"reason\":\"Joined as a Grade 5 teacher\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.roles.length()").value(1)).andExpect(jsonPath("$.roles[0].roleKey").value("teacher"))
                .andExpect(jsonPath("$.roles[0].scopeLevel").value("CAMPUS")).andReturn().getResponse().getContentAsString();
            String grantId = JsonPath.read(granted, "$.roles[0].grantId");
            mvc.perform(json(post(base), "dev-asha", "{\"roleKey\":\"teacher\",\"scopeLevel\":\"CAMPUS\",\"scopeId\":\"" + CAMPUS + "\",\"reason\":\"Giving it a second time\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("already_has_role"));

            String listed = mvc.perform(get("/api/v1/staff").header("Authorization", dev("dev-asha"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertEquals(1, ((java.util.List<?>) JsonPath.read(listed, "$[?(@.personId=='" + id + "')]")).size());

            mvc.perform(json(post(base + "/" + grantId + "/end"), "dev-asha", "{\"reason\":\"short\"}")).andExpect(status().isBadRequest());
            // A role ends at least one second after it began (the table requires valid_to after valid_from).
            Thread.sleep(1100);
            mvc.perform(json(post(base + "/" + grantId + "/end"), "dev-asha", "{\"reason\":\"Moved to another school\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.roles.length()").value(0));
            Thread.sleep(1100);
            mvc.perform(json(post(base + "/" + grantId + "/end"), "dev-asha", "{\"reason\":\"Ending it a second time\"}")).andExpect(status().isNotFound());

            assertEquals(1, ownerCount("select count(*) from audit_event where action = 'staff.role_granted' and resource_id = '" + id + "'"));
            assertEquals(1, ownerCount("select count(*) from audit_event where action = 'staff.role_ended' and resource_id = '" + id + "'"));
        } finally {
            if (id != null) {
                ownerUpdate("delete from role_grant where person_id = '" + id + "'; delete from person where id = '" + id + "';");
            }
        }
    }

    @Test
    void theLastGroupAdministratorCannotBeRemoved() throws Exception {
        String asha = "13000000-0000-4000-8000-000000000001";
        String grantId = ownerString("select id from role_grant where person_id = '" + asha + "' and role_key = 'group_admin' and valid_to is null");
        // Only count it a lock-out when she is the only one; the seed has one group administrator.
        long admins = ownerCount("select count(distinct person_id) from role_grant where role_key = 'group_admin' and (valid_to is null or valid_to > now())");
        if (admins == 1) {
            mvc.perform(json(post("/api/v1/staff/" + asha + "/roles/" + grantId + "/end"), "dev-asha", "{\"reason\":\"Trying to lock everyone out\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("last_administrator"));
        }
        assertEquals(1, ownerCount("select count(*) from role_grant where id = '" + grantId + "' and valid_to is null"));
    }
}
