package com.schoomano;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * B11: marks, locking, corrections with a reason, publishing report cards as frozen snapshots, and who may read them.
 * Uses its own class (Grade 6 B) and children so other tests' counts are unaffected.
 */
class AssessmentApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String SECTION_SQL = "md5('section-CBSE-K12-6-B')::uuid";
    static final String S1 = "13000000-0000-4000-8000-0000000b1101";
    static final String S2 = "13000000-0000-4000-8000-0000000b1102";
    static final String S3 = "13000000-0000-4000-8000-0000000b1103";

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return as(b, who).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static void fixture() throws Exception {
        String[][] kids = {{S1, "901", "Ishaan"}, {S2, "902", "Jiya"}, {S3, "903", "Kabir"}};
        for (String[] k : kids) {
            ownerUpdate("""
                insert into person (id, tenant_id, school_ref, given_name, family_name) values
                  ('%2$s', '%1$s', 'STU-IT-11%3$s', '%4$s', 'Synthetic') on conflict (id) do nothing;
                insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id, programme_id, class_section_id)
                  select '%1$s', '%2$s', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
                         '14000000-0000-4000-8000-000000000001', %5$s
                   where not exists (select 1 from student_enrollment where student_person_id = '%2$s');
                """.formatted(TENANT, k[0], k[1], k[2], SECTION_SQL));
        }
        // One guardian who may see academics for Ishaan, one who may not.
        String[][] guardians = {{"1111", "true"}, {"1112", "false"}};
        for (String[] g : guardians) {
            ownerUpdate("""
                insert into person (id, tenant_id, school_ref, given_name, family_name, mobile_e164)
                  values ('13000000-0000-4000-8000-0000000b%3$s', '%1$s', 'GRD-IT-%3$s', 'Parent%3$s', 'Synthetic', '+91984001%3$s') on conflict (id) do nothing;
                insert into identity (tenant_id, person_id, issuer, subject, email)
                  values ('%1$s', '13000000-0000-4000-8000-0000000b%3$s', 'dev', 'dev-g%3$s', 'g%3$s@example.invalid') on conflict do nothing;
                insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type, status,
                                                   can_view_attendance, can_view_academic, can_pay_fees, can_give_consent, can_receive_notices)
                  select '%1$s', '13000000-0000-4000-8000-0000000b%3$s', '%2$s', 'GUARDIAN', 'ACTIVE', true, %4$s, true, true, true
                   where not exists (select 1 from guardian_relationship where guardian_person_id = '13000000-0000-4000-8000-0000000b%3$s');
                """.formatted(TENANT, S1, g[0], g[1]));
        }
    }

    private static String id(String sql) throws Exception {
        return ownerString(sql);
    }

    @Test
    void marksLockPublishAndCorrectEndToEnd() throws Exception {
        fixture();
        String section = id("select " + SECTION_SQL);
        String term = id("""
            select t.id from academic_term t join class_section s on s.academic_year_id = t.academic_year_id
              join grade_level g on g.id = s.grade_level_id and g.programme_id = t.programme_id
             where s.id = %s and t.label = 'Term 1'""".formatted(SECTION_SQL));
        String eng = id("""
            select sub.id from subject sub join grade_level g on g.programme_id = sub.programme_id
              join class_section s on s.grade_level_id = g.id where s.id = %s and sub.code = 'ENG'""".formatted(SECTION_SQL));
        String math = id("""
            select sub.id from subject sub join grade_level g on g.programme_id = sub.programme_id
              join class_section s on s.grade_level_id = g.id where s.id = %s and sub.code = 'MATH'""".formatted(SECTION_SQL));
        String stateEng = id("select sub.id from subject sub join programme p on p.id = sub.programme_id where p.code = 'STATE-K10' and sub.code = 'ENG'");
        String base = "/api/v1/sections/" + section;

        // Creating: teachers can; a subject from another programme and duplicates are refused; outsiders see nothing.
        String create = "{\"subjectId\":\"%s\",\"termId\":\"" + term + "\",\"name\":\"Unit test 1\",\"maxMarks\":20,\"weightPercent\":50}";
        String engId = JsonPath.read(mvc.perform(json(post(base + "/assessments"), "dev-ravi", create.formatted(eng)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.students").value(3))
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post(base + "/assessments"), "dev-ravi", create.formatted(eng))).andExpect(status().isConflict());
        mvc.perform(json(post(base + "/assessments"), "dev-ravi", create.formatted(stateEng))).andExpect(status().isBadRequest());
        mvc.perform(json(post(base + "/assessments"), "dev-meera", create.formatted(eng))).andExpect(status().isNotFound());
        mvc.perform(json(post(base + "/assessments"), "dev-g1111", create.formatted(eng))).andExpect(status().isNotFound());
        String mathId = JsonPath.read(mvc.perform(json(post(base + "/assessments"), "dev-ravi",
            "{\"subjectId\":\"" + math + "\",\"termId\":\"" + term + "\",\"name\":\"Half-yearly\",\"maxMarks\":50,\"weightPercent\":100}"))
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");

        // Entering marks: bounded, no one outside the class, kinds consistent.
        String engUrl = "/api/v1/assessments/" + engId + "/marks";
        mvc.perform(json(put(engUrl), "dev-ravi", entries(S1, "SCORED", "21"))).andExpect(status().isBadRequest());
        mvc.perform(json(put(engUrl), "dev-ravi", entries("13000000-0000-4000-8000-000000000021", "SCORED", "5"))).andExpect(status().isBadRequest());
        mvc.perform(json(put(engUrl), "dev-ravi", entries(S1, "ABSENT", "5"))).andExpect(status().isBadRequest());
        mvc.perform(json(put(engUrl), "dev-ravi", "{\"entries\":[" + entry(S1, "SCORED", "18") + "," + entry(S2, "SCORED", "12") + "," + entry(S3, "ABSENT", null) + "]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(3));
        mvc.perform(json(put(engUrl), "dev-ravi", entries(S1, "SCORED", "18"))).andExpect(jsonPath("$.unchanged").value(1));
        mvc.perform(as(get(engUrl), "dev-ravi")).andExpect(status().isOk()).andExpect(jsonPath("$.marks", hasSize(3)));
        mvc.perform(as(get(engUrl), "dev-g1111")).andExpect(status().isNotFound());

        // Not ready: maths has no marks and neither assessment is locked.
        mvc.perform(as(get(base + "/report-preview?termId=" + term), "dev-ravi")).andExpect(status().isOk())
            .andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.blockers", hasItem(containsString("Not locked"))));
        mvc.perform(json(post(base + "/report-cards/publish?termId=" + term), "dev-asha", "{}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.problems").isArray());

        String mathUrl = "/api/v1/assessments/" + mathId + "/marks";
        mvc.perform(json(put(mathUrl), "dev-ravi", "{\"entries\":[" + entry(S1, "SCORED", "40") + "," + entry(S2, "EXEMPT", null) + "," + entry(S3, "SCORED", "25") + "]}"))
            .andExpect(status().isOk());
        mvc.perform(as(post("/api/v1/assessments/" + engId + "/lock"), "dev-ravi")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("LOCKED"));
        mvc.perform(as(post("/api/v1/assessments/" + mathId + "/lock"), "dev-ravi")).andExpect(status().isOk());

        // Ready: Ishaan 90.0 + 80.0 -> 85.0 (A2); Jiya only English 60.0 (C1); Kabir 0.0 + 50.0 -> 25.0 (E).
        mvc.perform(as(get(base + "/report-preview?termId=" + term), "dev-ravi")).andExpect(status().isOk())
            .andExpect(jsonPath("$.ready").value(true)).andExpect(jsonPath("$.students", hasSize(3)))
            .andExpect(jsonPath("$.students[?(@.name=='Ishaan Synthetic')].overallPercent").value(hasItem(85.0)))
            .andExpect(jsonPath("$.students[?(@.name=='Ishaan Synthetic')].overallGrade").value(hasItem("A2")))
            .andExpect(jsonPath("$.students[?(@.name=='Jiya Synthetic')].overallPercent").value(hasItem(60.0)))
            .andExpect(jsonPath("$.students[?(@.name=='Kabir Synthetic')].overallPercent").value(hasItem(25.0)))
            .andExpect(jsonPath("$.students[?(@.name=='Kabir Synthetic')].overallGrade").value(hasItem("E")));

        // Publishing needs REPORT_PUBLISH: a teacher is refused, the group admin may; republishing unchanged is a no-op.
        mvc.perform(json(post(base + "/report-cards/publish?termId=" + term), "dev-ravi", "{}")).andExpect(status().isForbidden());
        mvc.perform(json(post(base + "/report-cards/publish?termId=" + term), "dev-asha", "{}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.published").value(3));
        mvc.perform(json(post(base + "/report-cards/publish?termId=" + term), "dev-asha", "{}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.published").value(0)).andExpect(jsonPath("$.unchanged").value(3));

        // A locked mark changes only with a reason, and the change is recorded.
        mvc.perform(json(put(engUrl), "dev-ravi", entries(S2, "SCORED", "14"))).andExpect(status().isBadRequest());
        mvc.perform(json(put(engUrl), "dev-ravi", "{\"reason\":\"ok\",\"entries\":[" + entry(S2, "SCORED", "14") + "]}")).andExpect(status().isBadRequest());
        mvc.perform(json(put(engUrl), "dev-ravi", "{\"reason\":\"Re-marked after parent query\",\"entries\":[" + entry(S2, "SCORED", "14") + "]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(1));
        assertEquals(2, ownerCount("select count(*) from assessment_mark_history h join assessment_mark m on m.id = h.mark_id where m.assessment_id = '" + engId + "' and m.student_person_id = '" + S2 + "'"));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'assessment.marks_corrected' and resource_id = '" + engId + "'"));

        // The preview shows one child changed; the correction needs its own reason and becomes revision 2.
        mvc.perform(as(get(base + "/report-preview?termId=" + term), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$.students[?(@.changed==true)]", hasSize(1)));
        mvc.perform(json(post(base + "/report-cards/publish?termId=" + term), "dev-asha", "{}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(base + "/report-cards/publish?termId=" + term), "dev-asha", "{\"reason\":\"Corrected an English mark\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.published").value(1)).andExpect(jsonPath("$.unchanged").value(2));
        assertEquals(4, ownerCount("select count(*) from report_card where class_section_id = " + SECTION_SQL));

        // Reading: the guardian with view-academic sees the child's latest card only; others see nothing.
        mvc.perform(as(get("/api/v1/students/" + S1 + "/report-cards"), "dev-g1111")).andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1))).andExpect(jsonPath("$[0].revision").value(1))
            .andExpect(jsonPath("$[0].overallPercent").value(85.0)).andExpect(jsonPath("$[0].overallGrade").value("A2"))
            .andExpect(jsonPath("$[0].subjects", hasSize(2))).andExpect(jsonPath("$[0].gradeScale.bands").isArray());
        mvc.perform(as(get("/api/v1/students/" + S2 + "/report-cards"), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].revision").value(2)).andExpect(jsonPath("$[0].revisionReason").value("Corrected an English mark"))
            .andExpect(jsonPath("$[0].overallPercent").value(70.0));
        mvc.perform(as(get("/api/v1/students/" + S2 + "/report-cards"), "dev-g1111")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/students/" + S1 + "/report-cards"), "dev-g1112")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/students/" + S1 + "/report-cards"), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(get(base + "/report-preview?termId=" + term), "dev-meera")).andExpect(status().isNotFound());

        // A frozen card keeps its grade scale even if the scale later changes (the snapshot carries the bands).
        assertEquals(1, ownerCount("select count(*) from report_card where class_section_id = " + SECTION_SQL + " and jsonb_exists(grade_scale, 'bands') and revision = 1 and student_person_id = '" + S1 + "'"));
    }

    private static String entry(String student, String kind, String marks) {
        return "{\"studentId\":\"" + student + "\",\"kind\":\"" + kind + "\"" + (marks == null ? "" : ",\"marks\":" + marks) + "}";
    }

    private static String entries(String student, String kind, String marks) {
        return "{\"entries\":[" + entry(student, kind, marks) + "]}";
    }
}
