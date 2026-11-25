package com.schoomano;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B12: the admissions funnel, seat checks, enrolment into a class, and who may see it. Uses Grade 11 only. */
class AdmissionApiIT extends ApiITSupport {

    static final String YEAR = "15000000-0000-4000-8000-000000000001";
    static final String BASE = "/api/v1/academic-years/" + YEAR + "/admissions";
    static final String G11A = "md5('section-CBSE-K12-11-A')::uuid";
    static final String G11B = "md5('section-CBSE-K12-11-B')::uuid";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private static String body(String grade, String child, String mobile) {
        return """
            {"gradeLevelId":"%s","childGivenName":"%s","childFamilyName":"Applicant","guardianGivenName":"Parent %s",
             "guardianMobile":"%s","source":"WALK_IN"}""".formatted(grade, child, child, mobile);
    }

    private String create(String grade, String child, String mobile) throws Exception {
        return JsonPath.read(mvc.perform(json(post(BASE), "dev-asha", body(grade, child, mobile))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("ENQUIRY")).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void move(String id, String to, int expected, String extra) throws Exception {
        mvc.perform(json(post("/api/v1/admissions/" + id + "/transition"), "dev-asha", "{\"to\":\"" + to + "\"" + extra + "}"))
            .andExpect(status().is(expected));
    }

    @Test
    void funnelSeatsAndEnrolment() throws Exception {
        String grade = ownerString("select g.id from grade_level g join programme p on p.id = g.programme_id where p.code = 'CBSE-K12' and g.code = 'G11'");
        String g10a = ownerString("select md5('section-CBSE-K12-10-A')::uuid");
        String secA = ownerString("select " + G11A);
        String secB = ownerString("select " + G11B);
        ownerUpdate("update class_section set capacity = 1 where id in (" + G11A + ", " + G11B + ")");
        try {
            // Creating: the office can; teachers and other campuses cannot; bad mobile and duplicates are refused.
            mvc.perform(json(post(BASE), "dev-ravi", body(grade, "Nope", "98450 11111"))).andExpect(status().isNotFound());
            mvc.perform(json(post(BASE), "dev-meera", body(grade, "Nope", "98450 11111"))).andExpect(status().isNotFound());
            mvc.perform(json(post(BASE), "dev-asha", body(grade, "Bad", "12345"))).andExpect(status().isBadRequest());
            String a = create(grade, "Aarav", "98450 11101");
            create(grade, "Bela", "98450 11102");
            String c = create(grade, "Chirag", "98450 11103");
            mvc.perform(json(post(BASE), "dev-asha", body(grade, "aarav", "9845011101"))).andExpect(status().isConflict());
            mvc.perform(as(get(BASE), "dev-ravi")).andExpect(status().isNotFound());
            mvc.perform(as(get("/api/v1/admissions/" + a), "dev-meera")).andExpect(status().isNotFound());

            // Stages cannot be skipped; ending needs a reason.
            move(a, "OFFERED", 409, "");
            move(a, "ENROLLED", 400, "");
            move(a, "REJECTED", 400, "");
            for (String id : new String[] {a, id(BASE, "Bela"), c}) {
                move(id, "APPLIED", 200, "");
            }

            // Two seats in Grade 11: two offers fit, the third needs an override with a reason.
            move(a, "OFFERED", 200, "");
            move(id(BASE, "Bela"), "OFFERED", 200, "");
            move(c, "OFFERED", 409, "");
            move(c, "OFFERED", 409, ",\"overrideSeats\":true");
            move(c, "OFFERED", 200, ",\"overrideSeats\":true,\"reason\":\"Sibling of a current student\"");
            mvc.perform(as(get(BASE), "dev-asha")).andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[?(@.gradeName=='" + gradeName() + "')].held").value(org.hamcrest.Matchers.hasItem(3)));

            // Enrolling: only an accepted offer, into a class of the right grade that has room.
            mvc.perform(json(post("/api/v1/admissions/" + a + "/enrol"), "dev-asha", "{\"sectionId\":\"" + secA + "\"}")).andExpect(status().isConflict());
            move(a, "ACCEPTED", 200, "");
            move(id(BASE, "Bela"), "ACCEPTED", 200, "");
            mvc.perform(json(post("/api/v1/admissions/" + a + "/enrol"), "dev-asha", "{\"sectionId\":\"" + g10a + "\"}")).andExpect(status().isBadRequest());
            String student = JsonPath.read(mvc.perform(json(post("/api/v1/admissions/" + a + "/enrol"), "dev-asha", "{\"sectionId\":\"" + secA + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.studentId");
            mvc.perform(json(post("/api/v1/admissions/" + id(BASE, "Bela") + "/enrol"), "dev-asha", "{\"sectionId\":\"" + secA + "\"}")).andExpect(status().isConflict());
            mvc.perform(json(post("/api/v1/admissions/" + id(BASE, "Bela") + "/enrol"), "dev-asha", "{\"sectionId\":\"" + secB + "\"}")).andExpect(status().isOk());
            mvc.perform(json(post("/api/v1/admissions/" + a + "/enrol"), "dev-asha", "{\"sectionId\":\"" + secB + "\"}")).andExpect(status().isConflict());

            // The child, guardian and placement exist; the history tells the whole story.
            assertEquals(1, ownerCount("select count(*) from student_enrollment where student_person_id = '" + student + "' and class_section_id = " + G11A));
            assertEquals(1, ownerCount("select count(*) from guardian_relationship where student_person_id = '" + student + "' and status = 'ACTIVE' and can_pay_fees"));
            mvc.perform(as(get("/api/v1/students/" + student), "dev-asha")).andExpect(status().isOk());
            mvc.perform(as(get("/api/v1/admissions/" + a), "dev-asha")).andExpect(status().isOk())
                .andExpect(jsonPath("$.history", hasSize(5))).andExpect(jsonPath("$.application.status").value("ENROLLED"));

            // Withdrawing needs a reason; a closed application stays closed.
            move(c, "WITHDRAWN", 400, "");
            move(c, "WITHDRAWN", 200, ",\"reason\":\"Family chose another school\"");
            move(c, "ACCEPTED", 409, "");
            mvc.perform(as(get(BASE + "?status=WITHDRAWN"), "dev-asha")).andExpect(jsonPath("$.items", hasSize(1)));
            assertEquals(1, ownerCount("select count(*) from audit_event where action = 'admission.enrolled' and resource_id = '" + a + "'"));
        } finally {
            ownerUpdate("update class_section set capacity = 40 where id in (" + G11A + ", " + G11B + ")");
        }
    }

    private String id(String base, String child) throws Exception {
        java.util.List<String> ids = JsonPath.read(mvc.perform(as(get(base), "dev-asha")).andReturn().getResponse().getContentAsString(),
            "$.items[?(@.childName=='" + child + " Applicant')].id");
        return ids.get(0);
    }

    private static String gradeName() throws Exception {
        return ownerString("select g.name from grade_level g join programme p on p.id = g.programme_id where p.code = 'CBSE-K12' and g.code = 'G11'");
    }
}
