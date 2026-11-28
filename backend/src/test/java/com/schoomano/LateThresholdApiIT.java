package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** B63: a PRESENT mark made for today after the school day's start plus the allowed minutes is recorded as LATE. */
class LateThresholdApiIT extends ApiITSupport {

    static final String NORTH = "12000000-0000-4000-8000-000000000001";
    static final String ANAYA = "13000000-0000-4000-8000-000000000020";
    static final String RAVI = "13000000-0000-4000-8000-000000000002";

    @Test
    void presentMarkedAfterTheStartAndAllowedMinutesIsLateAndOnlyForToday() throws Exception {
        ZoneId zone = ZoneId.of("Asia/Kolkata");
        // The start below is midnight and the allowance 10 minutes: skip the few minutes after midnight in India.
        Assumptions.assumeTrue(LocalTime.now(zone).isAfter(LocalTime.of(2, 0)));
        LocalDate today = LocalDate.now(zone);
        String section = ownerString("select md5('section-CBSE-K12-5-A')::uuid::text");
        String url = "/api/v1/sections/" + section + "/attendance/batches";
        try {
            // Without a configured day start nothing changes.
            mvc.perform(post(url).header("Authorization", dev("dev-ravi")).header("Idempotency-Key", "late-key-0001").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"date\":\"" + today + "\",\"source\":\"DEVICE\",\"marks\":[{\"studentId\":\"" + ANAYA + "\",\"status\":\"PRESENT\",\"baseVersion\":0}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("PRESENT"));

            ownerUpdate("insert into config_version (tenant_id, config_key, scope_level, scope_id, version, value, effective_from, status, change_note, created_by, published_by, published_at) "
                + "values ('10000000-0000-4000-8000-000000000001', 'attendance.day_start', 'CAMPUS', '" + NORTH + "', 1, '\"00:00\"'::jsonb, '2020-01-01T00:00:00Z', "
                + "'PUBLISHED', 'late test', '13000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', now())");

            // Same child, marked present again on another day that is not today: left alone.
            String earlier = today.minusDays(40).toString();
            mvc.perform(post(url).header("Authorization", dev("dev-ravi")).header("Idempotency-Key", "late-key-0002").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"date\":\"" + earlier + "\",\"source\":\"DEVICE\",\"marks\":[{\"studentId\":\"" + ANAYA + "\",\"status\":\"PRESENT\",\"baseVersion\":0}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("PRESENT"));

            // Today, a different child is not enrolled here, so use Anaya's mark for tomorrow-free check: change today's mark.
            mvc.perform(post(url).header("Authorization", dev("dev-ravi")).header("Idempotency-Key", "late-key-0003").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"date\":\"" + today + "\",\"source\":\"DEVICE\",\"marks\":[{\"studentId\":\"" + ANAYA + "\",\"status\":\"PRESENT\",\"baseVersion\":1}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.results[0].status").value("LATE"));
            assertEquals(1, ownerCount("select count(*) from attendance_mark m join attendance_session s on s.id = m.session_id "
                + "where s.on_date = '" + today + "' and m.student_person_id = '" + ANAYA + "' and m.status = 'LATE'"));
        } finally {
            ownerUpdate("delete from config_version where config_key = 'attendance.day_start' and change_note = 'late test'");
        }
    }
}
