package com.schoomano;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B5: bell schedule, lessons, clash listing, publishing, substitutions and the teacher's day. North campus, grade 8. */
class TimetableApiIT extends ApiITSupport {

    static final String NORTH = "12000000-0000-4000-8000-000000000001";
    static final String NORTH_YEAR = "15000000-0000-4000-8000-000000000001";
    static final String CBSE = "14000000-0000-4000-8000-000000000001";
    static final String RAVI = "13000000-0000-4000-8000-000000000002";
    static final String SUNITA = "13000000-0000-4000-8000-000000000004";
    static final String MONDAY = "2027-01-11";

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private String lesson(String section, int day, String period, String subject, String teacher, int expected) throws Exception {
        var res = mvc.perform(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/timetable/entries"), "dev-asha", """
                {"sectionId":"%s","day":%d,"periodId":"%s","subjectId":"%s","teacherId":"%s"}"""
            .formatted(section, day, period, subject, teacher))).andExpect(status().is(expected)).andReturn();
        return expected == 201 ? JsonPath.read(res.getResponse().getContentAsString(), "$.id") : null;
    }

    @Test
    void theBellScheduleIsReadableChangeableByManagersAndMustBeSensible() throws Exception {
        mvc.perform(as(get("/api/v1/campuses/" + NORTH + "/periods"), "dev-ravi"))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(8)))
            .andExpect(jsonPath("$[0].label").value("Period 1"));
        mvc.perform(json(put("/api/v1/campuses/" + NORTH + "/periods"), "dev-ravi", """
                {"periods":[{"ordinal":1,"label":"P1","startsAt":"08:00","endsAt":"08:45","kind":"LESSON"}]}"""))
            .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/v1/campuses/" + NORTH + "/periods"), "dev-asha", """
                {"periods":[{"ordinal":1,"label":"P1","startsAt":"08:00","endsAt":"09:00","kind":"LESSON"},
                            {"ordinal":2,"label":"P2","startsAt":"08:45","endsAt":"09:30","kind":"LESSON"}]}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_schedule"))
            .andExpect(jsonPath("$.problems").isNotEmpty());
        // Nothing was changed by the refused request.
        mvc.perform(as(get("/api/v1/campuses/" + NORTH + "/periods"), "dev-ravi"))
            .andExpect(jsonPath("$", hasSize(8)));
    }

    @Test
    void roomsAreCreatedAndRetiredNotDeleted() throws Exception {
        mvc.perform(json(post("/api/v1/campuses/" + NORTH + "/rooms"), "dev-ravi", """
                {"code":"R201","name":"Room 201","capacity":35}"""))
            .andExpect(status().isForbidden());
        String id = JsonPath.read(mvc.perform(json(post("/api/v1/campuses/" + NORTH + "/rooms"), "dev-asha", """
                {"code":"R201","name":"Room 201","capacity":35}""")).andExpect(status().isCreated()).andReturn()
            .getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post("/api/v1/campuses/" + NORTH + "/rooms"), "dev-asha", """
                {"code":"R201","name":"Duplicate","capacity":35}"""))
            .andExpect(status().isConflict());
        mvc.perform(as(post("/api/v1/rooms/" + id + "/retire"), "dev-asha"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RETIRED"));
    }

    @Test
    void clashesBlockPublishingThenTheDatabaseKeepsThePublishedTimetableClean() throws Exception {
        String g8a = ownerString("select md5('section-CBSE-K12-8-A')::uuid::text");
        String g8b = ownerString("select md5('section-CBSE-K12-8-B')::uuid::text");
        String g7a = ownerString("select md5('section-CBSE-K12-7-A')::uuid::text");
        String maths = ownerString("select id::text from subject where programme_id = '" + CBSE + "' and code = 'MATH'");
        String sci = ownerString("select id::text from subject where programme_id = '" + CBSE + "' and code = 'SCI'");
        String p1 = ownerString("select md5('period-north-1')::uuid::text");
        String p2 = ownerString("select md5('period-north-2')::uuid::text");

        // A draft may hold a clash: Ravi in two sections at once.
        lesson(g8a, 1, p1, maths, RAVI, 201);
        String eightB = lesson(g8b, 1, p1, sci, RAVI, 201);
        mvc.perform(as(get("/api/v1/academic-years/" + NORTH_YEAR + "/timetable/status"), "dev-ravi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.clashes", hasSize(1)))
            .andExpect(jsonPath("$.clashes[0].kind").value("TEACHER"))
            .andExpect(jsonPath("$.clashes[0].text").value(containsString("Period 1")));
        mvc.perform(as(post("/api/v1/academic-years/" + NORTH_YEAR + "/timetable/publish"), "dev-asha"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("publish_rejected"))
            .andExpect(jsonPath("$.problems", hasSize(1)));

        // The same class cannot have two lessons in one period.
        lesson(g8a, 1, p1, sci, SUNITA, 409);

        // Fix the clash, then only a manager may publish.
        mvc.perform(json(patch("/api/v1/timetable/entries/" + eightB), "dev-asha", "{\"teacherId\":\"" + SUNITA + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.teacherId").value(SUNITA));
        mvc.perform(as(post("/api/v1/academic-years/" + NORTH_YEAR + "/timetable/publish"), "dev-ravi"))
            .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/academic-years/" + NORTH_YEAR + "/timetable/publish"), "dev-asha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PUBLISHED"))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.clashes", hasSize(0)));

        // Once published, a clashing edit is refused with a plain reason and nothing is written.
        mvc.perform(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/timetable/entries"), "dev-asha", """
                {"sectionId":"%s","day":1,"periodId":"%s","subjectId":"%s","teacherId":"%s"}"""
            .formatted(g7a, p1, maths, RAVI)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("timetable_rejected"))
            .andExpect(jsonPath("$.detail").value(containsString("already teaching")));

        // Substitutions: Ravi's second lesson, covered by Sunita who is free in Period 2.
        String ravisSecond = lesson(g8a, 1, p2, maths, RAVI, 201);
        mvc.perform(as(get("/api/v1/academic-years/" + NORTH_YEAR + "/substitutions/available?entryId=" + ravisSecond
                + "&date=" + MONDAY), "dev-asha"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + SUNITA + "')]").isNotEmpty())
            .andExpect(jsonPath("$[?(@.id=='" + RAVI + "')]").isEmpty());
        mvc.perform(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/substitutions"), "dev-asha", """
                {"entryId":"%s","onDate":"2027-01-12","substituteId":"%s","reason":"Ravi on training"}"""
            .formatted(ravisSecond, SUNITA)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("wrong_day"));
        mvc.perform(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/substitutions"), "dev-ravi", """
                {"entryId":"%s","onDate":"%s","substituteId":"%s","reason":"Ravi on training"}"""
            .formatted(ravisSecond, MONDAY, SUNITA)))
            .andExpect(status().isForbidden());
        String sub = JsonPath.read(mvc.perform(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/substitutions"), "dev-asha", """
                {"entryId":"%s","onDate":"%s","substituteId":"%s","reason":"Ravi on training"}"""
            .formatted(ravisSecond, MONDAY, SUNITA))).andExpect(status().isCreated())
            .andExpect(jsonPath("$.substituteName").isNotEmpty()).andReturn().getResponse().getContentAsString(), "$.id");
        // The teachers' own days.
        mvc.perform(as(get("/api/v1/timetable/me?date=" + MONDAY), "dev-sunita"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[1].coverFor").isNotEmpty());
        mvc.perform(as(get("/api/v1/timetable/me?date=" + MONDAY), "dev-ravi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].periodOrdinal").value(1));

        mvc.perform(as(get("/api/v1/academic-years/" + NORTH_YEAR + "/substitutions?from=" + MONDAY), "dev-ravi"))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(as(delete("/api/v1/substitutions/" + sub), "dev-asha")).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/timetable/me?date=" + MONDAY), "dev-ravi"))
            .andExpect(jsonPath("$", hasSize(2)));

        // Unpublishing needs a real reason, is audited, and returns the year to draft.
        mvc.perform(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/timetable/unpublish"), "dev-asha", "{\"reason\":\"short\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/v1/academic-years/" + NORTH_YEAR + "/timetable/unpublish"), "dev-asha",
                "{\"reason\":\"Rework after the new staff joined\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        org.junit.jupiter.api.Assertions.assertEquals(1L, ownerCount(
            "select count(*) from audit_event where action = 'timetable.unpublished'"));
        org.junit.jupiter.api.Assertions.assertTrue(ownerCount(
            "select count(*) from outbox_event where event_type = 'timetable.substitution_created'") >= 1);
    }

    @Test
    void peopleWithoutARoleSeeNothingOfTheTimetable() throws Exception {
        mvc.perform(as(get("/api/v1/academic-years/" + NORTH_YEAR + "/timetable"), "dev-meera"))
            .andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/academic-years/" + NORTH_YEAR + "/timetable"), "dev-other"))
            .andExpect(status().isNotFound());
    }
}
