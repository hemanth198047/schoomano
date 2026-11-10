package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B1 completion: year lifecycle, edits and removals, committed rollover. Uses South campus and its own rows. */
class AcademicLifecycleApiIT extends ApiITSupport {

    static final String NORTH = "12000000-0000-4000-8000-000000000001";
    static final String SOUTH = "12000000-0000-4000-8000-000000000002";
    static final String NORTH_YEAR = "15000000-0000-4000-8000-000000000001";
    static final String SOUTH_YEAR = "15000000-0000-4000-8000-000000000002";
    static final String CBSE = "14000000-0000-4000-8000-000000000001";
    static final String STATE_NORTH = "14000000-0000-4000-8000-000000000003";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String created(MockHttpServletRequestBuilder b) throws Exception {
        return JsonPath.read(mvc.perform(b).andExpect(status().isCreated()).andReturn().getResponse()
            .getContentAsString(), "$.id");
    }

    @Test
    void yearsAreCreatedPlannedAndMayNotOverlap() throws Exception {
        mvc.perform(json(post("/api/v1/campuses/" + NORTH + "/academic-years"), "dev-asha", """
                {"label":"2025-26","startsOn":"2025-04-01","endsOn":"2026-03-31"}"""))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PLANNED"));
        mvc.perform(json(post("/api/v1/campuses/" + NORTH + "/academic-years"), "dev-asha", """
                {"label":"Overlap","startsOn":"2026-12-01","endsOn":"2027-11-30"}"""))
            .andExpect(status().isConflict());
        mvc.perform(json(post("/api/v1/campuses/" + NORTH + "/academic-years"), "dev-ravi", """
                {"label":"2024-25","startsOn":"2024-04-01","endsOn":"2025-03-31"}"""))
            .andExpect(status().isForbidden());
    }

    @Test
    void termsCanBeMovedAndRemovedButNotIntoAnOverlap() throws Exception {
        String id = created(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/terms"), "dev-asha", """
                {"programmeId":"%s","label":"Orientation","startsOn":"2026-05-01","endsOn":"2026-05-10"}"""
            .formatted(STATE_NORTH)));
        mvc.perform(json(patch("/api/v1/terms/" + id), "dev-asha", """
                {"label":"Orientation","startsOn":"2026-05-01","endsOn":"2026-05-20"}"""))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.endsOn").value("2026-05-20"));
        mvc.perform(json(patch("/api/v1/terms/" + id), "dev-asha", """
                {"label":"Orientation","startsOn":"2026-05-01","endsOn":"2026-06-05"}"""))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("term_rejected"));
        mvc.perform(delete("/api/v1/terms/" + id).header("Authorization", dev("dev-ravi")))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/terms/" + id).header("Authorization", dev("dev-asha")))
            .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/terms/" + id).header("Authorization", dev("dev-asha")))
            .andExpect(status().isNotFound());
    }

    @Test
    void sectionsInUseCannotBeRemovedAndSubjectsAreRetiredNotDeleted() throws Exception {
        String g5a = ownerString("select md5('section-CBSE-K12-5-A')::uuid::text");
        mvc.perform(delete("/api/v1/sections/" + g5a).header("Authorization", dev("dev-asha")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("section_in_use"));
        mvc.perform(json(patch("/api/v1/sections/" + g5a), "dev-asha", "{\"capacity\":36}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.capacity").value(36));

        String g7 = ownerString("select md5('grade-CBSE-K12-7')::uuid::text");
        String d = created(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/sections"), "dev-asha", """
                {"gradeId":"%s","code":"D","capacity":30}""".formatted(g7)));
        mvc.perform(delete("/api/v1/sections/" + d).header("Authorization", dev("dev-asha")))
            .andExpect(status().isNoContent());

        String cs = ownerString("select id::text from subject where programme_id = '" + CBSE + "' and code = 'CS'");
        mvc.perform(json(patch("/api/v1/subjects/" + cs), "dev-ravi", "{\"status\":\"RETIRED\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(json(patch("/api/v1/subjects/" + cs), "dev-asha", "{\"status\":\"RETIRED\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RETIRED"))
            .andExpect(jsonPath("$.name").value("Computer Science"));
        mvc.perform(json(patch("/api/v1/grades/" + g7), "dev-asha", "{\"name\":\"Class VII\"}"))
            .andExpect(status().isOk());

        String ev = created(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/calendar-events"), "dev-asha", """
                {"kind":"EVENT","title":"Science fair","startsOn":"2027-02-10","endsOn":"2027-02-10"}"""));
        mvc.perform(delete("/api/v1/calendar-events/" + ev).header("Authorization", dev("dev-asha")))
            .andExpect(status().isNoContent());
    }

    @Test
    void rolloverCreatesNextYearPromotesStudentsOnceAndKeepsTheSourceYear() throws Exception {
        long sourceRowsBefore = ownerCount("select (select count(*) from academic_term where academic_year_id = '"
            + SOUTH_YEAR + "') + (select count(*) from class_section where academic_year_id = '" + SOUTH_YEAR + "')");
        String body = mvc.perform(post("/api/v1/academic-years/" + SOUTH_YEAR + "/rollover")
                .header("Authorization", dev("dev-asha")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.label").value("2027-28"))
            .andExpect(jsonPath("$.startsOn").value("2027-06-01"))
            .andExpect(jsonPath("$.termsCreated").value(2))
            .andExpect(jsonPath("$.sectionsCreated").value(12))
            .andExpect(jsonPath("$.studentsPlaced").value(1))
            .andExpect(jsonPath("$.studentsCompleted").value(0))
            .andReturn().getResponse().getContentAsString();
        String newYear = JsonPath.read(body, "$.yearId");

        // Vivaan (State Grade 3 A) is now in State Grade 4 A of the new year, linked to his old enrollment.
        assertEquals("G4/A", ownerString("""
            select g.code || '/' || s.code from student_enrollment e
              join class_section s on s.id = e.class_section_id join grade_level g on g.id = s.grade_level_id
             where e.academic_year_id = '%s' and e.student_person_id = '13000000-0000-4000-8000-000000000021'
               and e.promoted_from is not null""".formatted(newYear)));
        assertEquals(sourceRowsBefore, ownerCount("select (select count(*) from academic_term where academic_year_id = '"
            + SOUTH_YEAR + "') + (select count(*) from class_section where academic_year_id = '" + SOUTH_YEAR + "')"));
        assertEquals(1, ownerCount("select count(*) from outbox_event where event_type = 'academic.year_rolled_over'"
            + " and aggregate_id = '" + newYear + "'"));

        mvc.perform(post("/api/v1/academic-years/" + SOUTH_YEAR + "/rollover").header("Authorization", dev("dev-asha")))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("already_rolled_over"));

        // Activating the new year closes the old one; only one active year per campus.
        mvc.perform(post("/api/v1/academic-years/" + newYear + "/activate").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertEquals("CLOSED", ownerString("select status from academic_year where id = '" + SOUTH_YEAR + "'"));
        mvc.perform(post("/api/v1/academic-years/" + newYear + "/activate").header("Authorization", dev("dev-asha")))
            .andExpect(status().isConflict());
    }
}
