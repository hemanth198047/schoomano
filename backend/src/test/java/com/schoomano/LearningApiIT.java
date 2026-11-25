package com.schoomano;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B13: syllabus, coverage and homework. Works on Grade 5 A (CBSE) with Anaya's guardian (Meera) as the reader. */
class LearningApiIT extends ApiITSupport {

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return b.header("Authorization", dev(who)).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    @Test
    void syllabusCoverageAndHomework() throws Exception {
        String grade = ownerString("select g.id from grade_level g join programme p on p.id = g.programme_id where p.code = 'CBSE-K12' and g.code = 'G5'");
        String sec = ownerString("select md5('section-CBSE-K12-5-A')::uuid");
        String sci = ownerString("select s.id from subject s join programme p on p.id = s.programme_id where p.code = 'CBSE-K12' and s.code = 'SCI'");
        String stateEng = ownerString("select s.id from subject s join programme p on p.id = s.programme_id where p.code = 'STATE-K10' and s.code = 'ENG'");
        String syllabus = "/api/v1/grade-levels/" + grade + "/curriculum";

        // Syllabus: administrators write it, teachers can read it, a subject of another programme is refused.
        String unit1 = JsonPath.read(mvc.perform(json(post(syllabus), "dev-asha", "{\"subjectId\":\"" + sci + "\",\"title\":\"Plants and seeds\",\"outcomes\":\"Name the parts of a plant\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.ordinal").value(1)).andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(json(post(syllabus), "dev-asha", "{\"subjectId\":\"" + sci + "\",\"title\":\"Water cycle\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.ordinal").value(2));
        mvc.perform(json(post(syllabus), "dev-asha", "{\"subjectId\":\"" + stateEng + "\",\"title\":\"Wrong programme\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(syllabus), "dev-ravi", "{\"subjectId\":\"" + sci + "\",\"title\":\"Teacher edit\"}")).andExpect(status().isForbidden());
        mvc.perform(as(get(syllabus + "?subjectId=" + sci), "dev-ravi")).andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(as(get(syllabus), "dev-meera")).andExpect(status().isNotFound());

        // Coverage: a teacher marks a unit once; the percentage follows.
        String cov = "/api/v1/sections/" + sec + "/coverage";
        mvc.perform(as(get(cov + "?subjectId=" + sci), "dev-ravi")).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.covered").value(0));
        mvc.perform(json(post(cov), "dev-ravi", "{\"unitId\":\"" + unit1 + "\"}")).andExpect(status().isCreated());
        mvc.perform(json(post(cov), "dev-ravi", "{\"unitId\":\"" + unit1 + "\"}")).andExpect(status().isConflict());
        mvc.perform(json(post(cov), "dev-asha", "{\"unitId\":\"" + unit1 + "\",\"coveredOn\":\"2999-01-01\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(cov), "dev-meera", "{\"unitId\":\"" + unit1 + "\"}")).andExpect(status().isNotFound());
        mvc.perform(as(get(cov + "?subjectId=" + sci), "dev-ravi")).andExpect(jsonPath("$.covered").value(1))
            .andExpect(jsonPath("$.units[0].coveredOn").isNotEmpty()).andExpect(jsonPath("$.units[1].coveredOn").doesNotExist());

        // Homework: set by a teacher, seen by the guardian who may view academics, not by one who may not.
        String hw = "/api/v1/sections/" + sec + "/homework";
        String due = ownerString("select (current_date + 3)::text");
        mvc.perform(json(post(hw), "dev-ravi", "{\"subjectId\":\"" + stateEng + "\",\"title\":\"Wrong subject\",\"dueOn\":\"" + due + "\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(hw), "dev-ravi", "{\"subjectId\":\"" + sci + "\",\"title\":\"Late\",\"dueOn\":\"2020-01-01\"}")).andExpect(status().isBadRequest());
        mvc.perform(json(post(hw), "dev-meera", "{\"subjectId\":\"" + sci + "\",\"title\":\"Nope\",\"dueOn\":\"" + due + "\"}")).andExpect(status().isNotFound());
        String h1 = JsonPath.read(mvc.perform(json(post(hw), "dev-ravi", "{\"subjectId\":\"" + sci + "\",\"title\":\"Draw a plant\",\"instructions\":\"Label five parts\",\"dueOn\":\"" + due + "\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn().getResponse().getContentAsString(), "$.id");
        String anaya = "/api/v1/students/13000000-0000-4000-8000-000000000020/homework";
        mvc.perform(as(get(anaya), "dev-meera")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.title=='Draw a plant')]", hasSize(1)));
        mvc.perform(as(get(anaya), "dev-karan")).andExpect(status().isNotFound());
        mvc.perform(as(get(hw), "dev-ravi")).andExpect(status().isOk()).andExpect(jsonPath("$[?(@.title=='Draw a plant')]", hasSize(1)));

        // Cancelling hides it from parents and is recorded; a guardian cannot cancel it.
        mvc.perform(as(post("/api/v1/homework/" + h1 + "/cancel"), "dev-meera")).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/v1/homework/" + h1 + "/cancel"), "dev-ravi")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(as(get(anaya), "dev-meera")).andExpect(jsonPath("$[?(@.title=='Draw a plant')]", hasSize(0)));
        org.junit.jupiter.api.Assertions.assertEquals(1, ownerCount("select count(*) from audit_event where action = 'homework.cancelled' and resource_id = '" + h1 + "'"));
    }
}
