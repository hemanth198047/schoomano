package com.schoomano;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B1 acceptance: per-programme structure, term rules, audited changes, rollover preview, grade scales. */
class AcademicStructureApiIT extends ApiITSupport {

    static final String NORTH = "12000000-0000-4000-8000-000000000001";
    static final String NORTH_YEAR = "15000000-0000-4000-8000-000000000001";
    static final String CBSE = "14000000-0000-4000-8000-000000000001";
    static final String STATE_NORTH = "14000000-0000-4000-8000-000000000003";
    static final String STATE_SOUTH = "14000000-0000-4000-8000-000000000002";

    private static MockHttpServletRequestBuilder postJson(String url, String who, String body) {
        return post(url).header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void adminSeesStructurePerProgrammeForTheActiveYear() throws Exception {
        mvc.perform(get("/api/v1/campuses/" + NORTH + "/academic-structure").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.year.label").value("2026-27"))
            .andExpect(jsonPath("$.programmes", hasSize(2)))
            .andExpect(jsonPath("$.programmes[?(@.code=='CBSE-K12')].grades[*]", hasSize(12)))
            .andExpect(jsonPath("$.programmes[?(@.code=='STATE-K10')].grades[*]", hasSize(10)))
            .andExpect(jsonPath("$.programmes[?(@.code=='CBSE-K12')].grades[4].sections[0].students").value(1))
            .andExpect(jsonPath("$.programmes[?(@.code=='CBSE-K12')].subjects[*].code", hasItem("MATH")))
            .andExpect(jsonPath("$.calendar[*].title", hasItem("Independence Day")));
    }

    @Test
    void teacherReadsButCannotChangeStructure() throws Exception {
        mvc.perform(get("/api/v1/campuses/" + NORTH + "/academic-structure").header("Authorization", dev("dev-ravi")))
            .andExpect(status().isOk());
        mvc.perform(postJson("/api/v1/academic-years/" + NORTH_YEAR + "/terms", "dev-ravi", """
                {"programmeId":"%s","label":"Extra","startsOn":"2026-04-01","endsOn":"2026-04-10"}""".formatted(STATE_NORTH)))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/academic-years/" + NORTH_YEAR + "/rollover-preview").header("Authorization", dev("dev-ravi")))
            .andExpect(status().isForbidden());
    }

    @Test
    void guardiansAndOtherTenantsCannotSeeOrProbeTheStructure() throws Exception {
        mvc.perform(get("/api/v1/campuses/" + NORTH + "/academic-structure").header("Authorization", dev("dev-meera")))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/campuses/" + NORTH + "/academic-structure").header("Authorization", dev("dev-other")))
            .andExpect(status().isNotFound());
        mvc.perform(postJson("/api/v1/programmes/" + CBSE + "/subjects", "dev-other", """
                {"code":"HACK","name":"Injected"}"""))
            .andExpect(status().isNotFound());
    }

    @Test
    void overlappingTermIsRejectedWithTheReason() throws Exception {
        mvc.perform(postJson("/api/v1/academic-years/" + NORTH_YEAR + "/terms", "dev-asha", """
                {"programmeId":"%s","label":"Mid term","startsOn":"2026-09-01","endsOn":"2026-10-15"}""".formatted(CBSE)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("term_rejected"))
            .andExpect(jsonPath("$.problems[0]", containsString("Term 1")));
    }

    @Test
    void termOfAProgrammeOnAnotherCampusIsRejected() throws Exception {
        mvc.perform(postJson("/api/v1/academic-years/" + NORTH_YEAR + "/terms", "dev-asha", """
                {"programmeId":"%s","label":"Bridge","startsOn":"2026-04-01","endsOn":"2026-04-10"}""".formatted(STATE_SOUTH)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("wrong_campus"));
    }

    @Test
    void adminAddsTermSectionAndHolidayAndEachChangeIsAudited() throws Exception {
        long before = ownerCount("select count(*) from audit_event where action like 'academic.%'");
        mvc.perform(postJson("/api/v1/academic-years/" + NORTH_YEAR + "/terms", "dev-asha", """
                {"programmeId":"%s","label":"Bridge course","startsOn":"2026-04-01","endsOn":"2026-04-30"}"""
                .formatted(STATE_NORTH)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.label").value("Bridge course"));

        String gradeId = JsonPath.read(mvc.perform(get("/api/v1/campuses/" + NORTH + "/academic-structure")
                .header("Authorization", dev("dev-asha"))).andReturn().getResponse().getContentAsString(),
            "$.programmes[?(@.code=='CBSE-K12')].grades[5].id").toString().replaceAll("[\\[\\]\"]", "");
        String section = """
            {"gradeId":"%s","code":"C","capacity":35}""".formatted(gradeId);
        mvc.perform(postJson("/api/v1/academic-years/" + NORTH_YEAR + "/sections", "dev-asha", section))
            .andExpect(status().isCreated());
        mvc.perform(postJson("/api/v1/academic-years/" + NORTH_YEAR + "/sections", "dev-asha", section))
            .andExpect(status().isConflict());

        mvc.perform(postJson("/api/v1/academic-years/" + NORTH_YEAR + "/calendar-events", "dev-asha", """
                {"kind":"HOLIDAY","title":"Founders' Day","startsOn":"2026-12-04","endsOn":"2026-12-04"}"""))
            .andExpect(status().isCreated());
        mvc.perform(postJson("/api/v1/academic-years/" + NORTH_YEAR + "/calendar-events", "dev-asha", """
                {"kind":"HOLIDAY","title":"Summer","startsOn":"2027-05-01","endsOn":"2027-05-30"}"""))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("outside_year"));

        long after = ownerCount("select count(*) from audit_event where action like 'academic.%'");
        assertEquals(before + 3, after, "term, section and holiday each leave one audit event");
    }

    @Test
    void rolloverPreviewProposesNextYearAndWritesNothing() throws Exception {
        String tables = "(select count(*) from academic_year) + (select count(*) from academic_term)"
            + " + (select count(*) from class_section) + (select count(*) from student_enrollment)";
        long before = ownerCount("select " + tables);
        String body = mvc.perform(post("/api/v1/academic-years/" + NORTH_YEAR + "/rollover-preview")
                .header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.label").value("2027-28"))
            .andExpect(jsonPath("$.dates.startsOn").value("2027-04-01"))
            .andExpect(jsonPath("$.dates.endsOn").value("2028-03-31"))
            .andExpect(jsonPath("$.promotions[?(@.fromGrade=='G5' && @.programmeCode=='CBSE-K12')].toGrade").value("G6"))
            .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"terms\""));
        assertEquals(before, ownerCount("select " + tables), "preview must not write academic data");
    }

    @Test
    void gradeScaleIsEffectiveDatedPerProgrammeAndValidated() throws Exception {
        String url = "/api/v1/config/assessment.grade_scale";
        mvc.perform(get(url).header("Authorization", dev("dev-asha")).param("programmeId", CBSE)
                .param("asOf", "2026-10-01T00:00:00+05:30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value.bands[0].grade").value("A1"))
            .andExpect(jsonPath("$.definedAtLevel").value("PROGRAMME"));

        mvc.perform(postJson(url + "/drafts", "dev-asha", """
                {"scopeLevel":"PROGRAMME","scopeId":"%s","value":{"bands":[{"grade":"A","minPercent":50},{"grade":"B","minPercent":20}]},
                 "effectiveFrom":"2027-01-01T00:00:00+05:30","changeNote":"no zero band"}""".formatted(CBSE)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_value"));
        mvc.perform(postJson(url + "/drafts", "dev-asha", """
                {"scopeLevel":"GROUP","scopeId":"10000000-0000-4000-8000-000000000001",
                 "value":{"bands":[{"grade":"P","minPercent":40},{"grade":"F","minPercent":0}]},
                 "effectiveFrom":"2027-01-01T00:00:00+05:30","changeNote":"wrong scope"}"""))
            .andExpect(status().isBadRequest());

        String created = mvc.perform(postJson(url + "/drafts", "dev-asha", """
                {"scopeLevel":"PROGRAMME","scopeId":"%s",
                 "value":{"bands":[{"grade":"P","minPercent":40},{"grade":"F","minPercent":0}]},
                 "effectiveFrom":"2027-01-01T00:00:00+05:30","changeNote":"Pass/fail pilot for term 2"}""".formatted(CBSE)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mvc.perform(post("/api/v1/config/drafts/" + id + "/preview").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].programmeName").value("CBSE Grades 1–12"))
            .andExpect(jsonPath("$[0].changed").value(true));
        mvc.perform(post("/api/v1/config/drafts/" + id + "/publish").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk());

        // Reports computed for an earlier date keep the scale that applied then.
        mvc.perform(get(url).header("Authorization", dev("dev-asha")).param("programmeId", CBSE)
                .param("asOf", "2026-10-01T00:00:00+05:30"))
            .andExpect(jsonPath("$.value.bands[0].grade").value("A1"));
        mvc.perform(get(url).header("Authorization", dev("dev-asha")).param("programmeId", CBSE)
                .param("asOf", "2027-02-01T00:00:00+05:30"))
            .andExpect(jsonPath("$.value.bands[0].grade").value("P"));
        // Other programmes are untouched.
        mvc.perform(get(url).header("Authorization", dev("dev-asha")).param("programmeId", STATE_NORTH)
                .param("asOf", "2027-02-01T00:00:00+05:30"))
            .andExpect(jsonPath("$.value.bands[0].grade").value("A"));
    }
}
