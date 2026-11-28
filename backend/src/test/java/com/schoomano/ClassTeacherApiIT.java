package com.schoomano;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B62: class teachers, and who may mark a section once it has one. Grade 5 A, North. Ravi and Sunita are teachers there. */
class ClassTeacherApiIT extends ApiITSupport {

    static final String RAVI = "13000000-0000-4000-8000-000000000002";
    static final String SUNITA = "13000000-0000-4000-8000-000000000004";
    static final String ANAYA = "13000000-0000-4000-8000-000000000020";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String batch(String day) {
        return "{\"date\":\"" + day + "\",\"source\":\"DEVICE\",\"marks\":[{\"studentId\":\"" + ANAYA + "\",\"status\":\"PRESENT\",\"baseVersion\":0}]}";
    }

    @Test
    void onceASectionHasAClassTeacherOnlyThatTeacherAndAdministratorsMayMarkIt() throws Exception {
        String section = ownerString("select md5('section-CBSE-K12-5-A')::uuid::text");
        String teachers = "/api/v1/sections/" + section + "/class-teachers";
        String marking = "/api/v1/sections/" + section + "/attendance/batches";
        try {
            // Nobody is class teacher yet: any teacher of the campus can mark, as before.
            mvc.perform(get(teachers).header("Authorization", dev("dev-ravi"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

            // Only someone who manages the academic structure may assign.
            mvc.perform(json(post(teachers), "dev-ravi", "{\"teacherPersonId\":\"" + RAVI + "\"}")).andExpect(status().isForbidden());
            // The person must hold the teacher role.
            mvc.perform(json(post(teachers), "dev-asha", "{\"teacherPersonId\":\"13000000-0000-4000-8000-000000000001\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("not_a_teacher"));

            String assigned = mvc.perform(json(post(teachers), "dev-asha", "{\"teacherPersonId\":\"" + RAVI + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].teacherPersonId").value(RAVI))
                .andReturn().getResponse().getContentAsString();
            String assignmentId = JsonPath.read(assigned, "$[0].id");
            mvc.perform(json(post(teachers), "dev-asha", "{\"teacherPersonId\":\"" + RAVI + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("already_assigned"));

            // Another teacher is held back; the class teacher is not (administrators correct marks, they do not take attendance).
            mvc.perform(json(post(marking).header("Idempotency-Key", "ct-key-0001"), "dev-sunita", batch("2026-10-09"))).andExpect(status().isForbidden());
            mvc.perform(json(post(marking).header("Idempotency-Key", "ct-key-0002"), "dev-ravi", batch("2026-10-09"))).andExpect(status().isOk());

            // Ending needs a reason; after it the section is open to its campus's teachers again.
            mvc.perform(json(post(teachers + "/" + assignmentId + "/end"), "dev-asha", "{\"reason\":\"short\"}")).andExpect(status().isBadRequest());
            mvc.perform(json(post(teachers + "/" + assignmentId + "/end"), "dev-asha", "{\"reason\":\"Moved to Grade 6\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
            mvc.perform(json(post(teachers + "/" + assignmentId + "/end"), "dev-asha", "{\"reason\":\"Ending it twice over\"}")).andExpect(status().isNotFound());
            mvc.perform(json(post(marking).header("Idempotency-Key", "ct-key-0004"), "dev-sunita", batch("2026-10-13"))).andExpect(status().isOk());

            assertAudit("class_teacher.assigned", section, 1);
            assertAudit("class_teacher.ended", section, 1);
        } finally {
            ownerUpdate("alter table class_teacher disable trigger class_teacher_guard; delete from class_teacher where class_section_id = '" + section
                + "'; alter table class_teacher enable trigger class_teacher_guard;");
        }
    }

    private void assertAudit(String action, String section, long expected) throws Exception {
        long n = ownerCount("select count(*) from audit_event where action = '" + action + "' and resource_id = '" + section + "'");
        if (n < expected) {
            throw new AssertionError(action + " was not audited");
        }
    }
}
