package com.schoomano;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/** B64: the section report counts what was marked, never invents a percentage, and keeps to the caller's campus. */
class SectionAttendanceReportApiIT extends ApiITSupport {

    static final String ANAYA = "13000000-0000-4000-8000-000000000020";

    @Test
    void reportsCountsPerStudentAndRefusesBadRangesAndOutsiders() throws Exception {
        String section = ownerString("select md5('section-CBSE-K12-5-A')::uuid::text");
        String url = "/api/v1/sections/" + section + "/attendance/report";
        // A range in the far past: students are listed, nothing was taken, so no percentage.
        mvc.perform(get(url + "?from=2001-01-01&to=2001-01-31").header("Authorization", dev("dev-ravi"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.daysTaken").value(0)).andExpect(jsonPath("$.students[0].percent").doesNotExist())
            .andExpect(jsonPath("$.sectionLabel").exists());
        // October 2026 holds marks written by the other attendance tests, so only the shape is checked here.
        mvc.perform(get(url + "?from=2026-10-01&to=2026-10-31").header("Authorization", dev("dev-asha"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.students[?(@.studentId=='" + ANAYA + "')].present").exists());
        mvc.perform(get(url + "?from=2026-10-31&to=2026-10-01").header("Authorization", dev("dev-ravi"))).andExpect(status().isBadRequest());
        mvc.perform(get(url + "?from=2026-01-01&to=2026-12-31").header("Authorization", dev("dev-ravi"))).andExpect(status().isBadRequest());
        // Another school group cannot see the section at all.
        mvc.perform(get(url + "?from=2026-10-01&to=2026-10-31").header("Authorization", dev("dev-other"))).andExpect(status().isNotFound());
    }
}
