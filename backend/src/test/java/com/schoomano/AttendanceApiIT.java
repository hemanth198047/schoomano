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

/** B6: roster, idempotent batches, office corrections, conflicts and their resolution. Anaya, Grade 5 A, North. */
class AttendanceApiIT extends ApiITSupport {

    static final String NORTH = "12000000-0000-4000-8000-000000000001";
    static final String ANAYA = "13000000-0000-4000-8000-000000000020";
    static final String SOUTH_STUDENT = "13000000-0000-4000-8000-000000000021";
    static final String DAY = "2026-10-05";

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private static MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder b, String who, String key, String json) {
        MockHttpServletRequestBuilder r = as(b, who).contentType(MediaType.APPLICATION_JSON).content(json);
        return key == null ? r : r.header("Idempotency-Key", key);
    }

    private static String batch(String date, String student, String status, int base) {
        return """
            {"date":"%s","source":"DEVICE","marks":[{"studentId":"%s","status":"%s","baseVersion":%d}]}"""
            .formatted(date, student, status, base);
    }

    @Test
    void offlineMarkingRetriesAreSafeAndOfficeChangesAreNeverOverwritten() throws Exception {
        String section = ownerString("select md5('section-CBSE-K12-5-A')::uuid::text");
        String url = "/api/v1/sections/" + section + "/attendance";

        mvc.perform(as(get(url + "?date=" + DAY), "dev-ravi"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.students[?(@.studentId=='" + ANAYA + "')].status").isEmpty())
            .andExpect(jsonPath("$.openConflicts").value(0));
        mvc.perform(as(get(url + "?date=" + DAY), "dev-meera")).andExpect(status().isNotFound());

        // A phone marks Anaya absent; a retry with the same key changes nothing.
        mvc.perform(body(post(url + "/batches"), "dev-ravi", null, batch(DAY, ANAYA, "ABSENT", 0)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("idempotency_key_required"));
        mvc.perform(body(post(url + "/batches"), "dev-ravi", "key-AAAA0001", batch(DAY, ANAYA, "ABSENT", 0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.results[0].outcome").value("CREATED"))
            .andExpect(jsonPath("$.results[0].version").value(1))
            .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(body(post(url + "/batches"), "dev-ravi", "key-AAAA0001", batch(DAY, ANAYA, "ABSENT", 0)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replayed").value(true))
            .andExpect(jsonPath("$.results[0].outcome").value("CREATED"));
        mvc.perform(body(post(url + "/batches"), "dev-ravi", "key-AAAA0001", batch(DAY, ANAYA, "PRESENT", 0)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency_key_reused"));
        assertEquals(1L, ownerCount("select count(*) from attendance_mark_history"));
        assertEquals(1L, ownerCount("select count(*) from outbox_event where event_type = 'attendance.absent'"));

        // The office corrects while the phone is offline; the phone's stale value becomes a visible conflict.
        mvc.perform(body(post(url + "/corrections"), "dev-ravi", null,
                "{\"date\":\"" + DAY + "\",\"studentId\":\"" + ANAYA + "\",\"status\":\"LEAVE\",\"reason\":\"Medical leave note\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(body(post(url + "/corrections"), "dev-asha", null,
                "{\"date\":\"" + DAY + "\",\"studentId\":\"" + ANAYA + "\",\"status\":\"LEAVE\",\"reason\":\"Medical leave note\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("UPDATED"))
            .andExpect(jsonPath("$.version").value(2));
        mvc.perform(body(post(url + "/batches"), "dev-ravi", "key-AAAA0002", batch(DAY, ANAYA, "PRESENT", 1)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conflicts").value(1))
            .andExpect(jsonPath("$.results[0].outcome").value("CONFLICT"))
            .andExpect(jsonPath("$.results[0].status").value("LEAVE"));
        mvc.perform(as(get(url + "?date=" + DAY), "dev-ravi"))
            .andExpect(jsonPath("$.openConflicts").value(1))
            .andExpect(jsonPath("$.students[?(@.studentId=='" + ANAYA + "')].status").value("LEAVE"));

        // Only the office sees and resolves conflicts.
        mvc.perform(as(get("/api/v1/campuses/" + NORTH + "/attendance/conflicts"), "dev-ravi")).andExpect(status().isForbidden());
        String conflict = JsonPath.read(mvc.perform(as(get("/api/v1/campuses/" + NORTH + "/attendance/conflicts"), "dev-asha"))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].currentStatus").value("LEAVE"))
            .andExpect(jsonPath("$[0].attemptedStatus").value("PRESENT"))
            .andReturn().getResponse().getContentAsString(), "$[0].id");
        String resolve = "/api/v1/attendance/conflicts/" + conflict + "/resolve";
        mvc.perform(body(post(resolve), "dev-ravi", null, "{\"useAttempt\":true,\"reason\":\"fine\"}")).andExpect(status().isForbidden());
        mvc.perform(body(post(resolve), "dev-asha", null, "{\"useAttempt\":true,\"reason\":\"Teacher saw the child\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.resolution").value("USED_ATTEMPT"));
        mvc.perform(body(post(resolve), "dev-asha", null, "{\"useAttempt\":true,\"reason\":\"Again by mistake\"}"))
            .andExpect(status().isConflict());
        mvc.perform(as(get(url + "?date=" + DAY), "dev-ravi"))
            .andExpect(jsonPath("$.openConflicts").value(0))
            .andExpect(jsonPath("$.students[?(@.studentId=='" + ANAYA + "')].status").value("PRESENT"))
            .andExpect(jsonPath("$.students[?(@.studentId=='" + ANAYA + "')].version").value(3));
        // Every step is in the history.
        assertEquals(3L, ownerCount("select count(*) from attendance_mark_history"));
    }

    @Test
    void badBatchesAreRefusedClearly() throws Exception {
        String section = ownerString("select md5('section-CBSE-K12-5-A')::uuid::text");
        String url = "/api/v1/sections/" + section + "/attendance/batches";
        String tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1).toString();
        mvc.perform(body(post(url), "dev-ravi", "key-BBBB0001", batch(tomorrow, ANAYA, "PRESENT", 0)))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attendance_rejected"));
        mvc.perform(body(post(url), "dev-ravi", "key-BBBB0002", batch(DAY, SOUTH_STUDENT, "PRESENT", 0)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].outcome").value("REJECTED"));
        mvc.perform(body(post(url), "dev-ravi", "key-BBBB0003", batch(DAY, ANAYA, "HOLIDAY", 0)))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_status"));
        mvc.perform(body(post(url), "dev-meera", "key-BBBB0004", batch(DAY, ANAYA, "PRESENT", 0)))
            .andExpect(status().isNotFound());
        mvc.perform(body(post(url), "dev-asha", "key-BBBB0005", batch(DAY, ANAYA, "PRESENT", 0)))
            .andExpect(status().isForbidden());
    }
}
