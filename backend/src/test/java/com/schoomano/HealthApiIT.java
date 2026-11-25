package com.schoomano;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B16: the nurse keeps the record and the infirmary log; teachers see only the emergency flag. */
class HealthApiIT extends ApiITSupport {

    static final String YEAR = "15000000-0000-4000-8000-000000000001";
    static final String ANAYA = "13000000-0000-4000-8000-000000000020";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    @Test
    void nurseKeepsTheRecordAndEveryoneElseSeesAtMostTheFlag() throws Exception {
        String record = "/api/v1/students/" + ANAYA + "/health-record";
        String body = "{\"bloodGroup\":\"O+\",\"allergies\":\"Peanuts, penicillin\",\"conditions\":\"Asthma\","
            + "\"medicines\":\"Salbutamol inhaler as needed\",\"emergencyFlag\":\"Severe nut allergy, carries EpiPen\"}";

        // Nobody but the nurse can write or read the record.
        for (String who : new String[] {"dev-ravi", "dev-asha", "dev-farah", "dev-meera", "dev-karan", "dev-other"}) {
            mvc.perform(json(put(record), who, body)).andExpect(status().isNotFound());
            mvc.perform(as(get(record), who)).andExpect(status().isNotFound());
            mvc.perform(json(post("/api/v1/students/" + ANAYA + "/health-visits"), who,
                "{\"complaint\":\"Headache\",\"outcome\":\"OTHER\"}")).andExpect(status().isNotFound());
        }
        mvc.perform(json(put("/api/v1/students/13000000-0000-4000-8000-000000000004/health-record"), "dev-nisha", body))
            .andExpect(status().isNotFound());
        mvc.perform(json(put(record), "dev-nisha", body.replace("O+", "Z+"))).andExpect(status().isBadRequest());
        mvc.perform(json(put(record), "dev-nisha", body.replace("Severe nut allergy, carries EpiPen", "x"))).andExpect(status().isBadRequest());

        mvc.perform(json(put(record), "dev-nisha", body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.allergies").value("Peanuts, penicillin")).andExpect(jsonPath("$.updatedBy").value("Nisha Nurse (synthetic)"));
        // Saving again replaces the record.
        mvc.perform(json(put(record), "dev-nisha", body.replace("Asthma", "Asthma, mild"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.conditions").value("Asthma, mild"));

        // The infirmary log is permanent and newest first.
        mvc.perform(json(post("/api/v1/students/" + ANAYA + "/health-visits"), "dev-nisha", "{\"complaint\":\"\",\"outcome\":\"OTHER\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/students/" + ANAYA + "/health-visits"), "dev-nisha",
            "{\"complaint\":\"Wheezing after PE\",\"actionTaken\":\"Inhaler given, rested 20 minutes\",\"outcome\":\"RETURNED_TO_CLASS\",\"guardianInformed\":true}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.recordedBy").value("Nisha Nurse (synthetic)"));
        mvc.perform(as(get(record), "dev-nisha")).andExpect(status().isOk())
            .andExpect(jsonPath("$.visits", hasSize(1))).andExpect(jsonPath("$.visits[0].guardianInformed").value(true))
            .andExpect(jsonPath("$.bloodGroup").value("O+"));

        // Teachers see only the one-line flag, never the record.
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/health-flag"), "dev-ravi")).andExpect(status().isOk())
            .andExpect(jsonPath("$.flag").value("Severe nut allergy, carries EpiPen")).andExpect(jsonPath("$.allergies").doesNotExist());
        mvc.perform(as(get("/api/v1/academic-years/" + YEAR + "/health-flags"), "dev-ravi")).andExpect(status().isOk())
            .andExpect(jsonPath("$[*].studentId").value(hasItem(ANAYA)));
        // Guardians (even of this child), the other school and the safeguarding lead get nothing.
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/health-flag"), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/students/" + ANAYA + "/health-flag"), "dev-other")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/academic-years/" + YEAR + "/health-flags"), "dev-meera")).andExpect(status().isNotFound());

        // Reading and writing leave a trail.
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'health.record_viewed' and resource_id = '" + ANAYA + "'"));
        assertEquals(2, ownerCount("select count(*) from audit_event where action = 'health.record_saved' and resource_id = '" + ANAYA + "'"));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'health.visit_recorded'"));
    }
}
