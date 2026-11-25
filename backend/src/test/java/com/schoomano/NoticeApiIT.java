package com.schoomano;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.schoomano.platform.NoticeDispatcher;
import com.schoomano.platform.SmsSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B7: announcements, who is reached, inbox, SMS with a stand-in sender, and notices made from outbox events. */
@Import(NoticeApiIT.Capture.class)
class NoticeApiIT extends ApiITSupport {

    static final String NORTH = "12000000-0000-4000-8000-000000000001";
    static final String NORTH_YEAR = "15000000-0000-4000-8000-000000000001";
    static final String CBSE = "14000000-0000-4000-8000-000000000001";
    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String ZOYA = "13000000-0000-4000-8000-000000000701";

    static final List<String[]> TEXTS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Capture {
        @Bean
        @Primary
        SmsSender captureSms() {
            return new SmsSender() {
                @Override
                public void sendOtp(String phone, String code) { }

                @Override
                public void sendText(String phone, String text) {
                    TEXTS.add(new String[] {phone, text});
                }
            };
        }
    }

    @Autowired
    NoticeDispatcher dispatcher;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String who, String body) {
        return as(b, who).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Zoya in Grade 5 B, with an active guardian, a notices-only restricted guardian and a suspended one. */
    private static void fixture() throws Exception {
        ownerUpdate("""
            insert into person (id, tenant_id, school_ref, given_name, family_name) values
              ('13000000-0000-4000-8000-000000000701', '%1$s', 'STU-IT-701', 'Zoya', 'Synthetic')
              on conflict (id) do nothing;
            insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id, programme_id, class_section_id)
              select '%1$s', '13000000-0000-4000-8000-000000000701', '12000000-0000-4000-8000-000000000001',
                     '15000000-0000-4000-8000-000000000001', '14000000-0000-4000-8000-000000000001', md5('section-CBSE-K12-5-B')::uuid
               where not exists (select 1 from student_enrollment where student_person_id = '13000000-0000-4000-8000-000000000701');
            """.formatted(TENANT));
        String[][] guardians = {{"711", "ACTIVE", "true"}, {"712", "RESTRICTED", "false"}, {"713", "SUSPENDED", "true"}};
        for (String[] g : guardians) {
            String id = "13000000-0000-4000-8000-000000000" + g[0];
            ownerUpdate("""
                insert into person (id, tenant_id, school_ref, given_name, family_name, mobile_e164)
                  values ('%1$s', '%2$s', 'GRD-IT-%3$s', 'Parent%3$s', 'Synthetic', '+919820000%3$s') on conflict (id) do nothing;
                insert into identity (tenant_id, person_id, issuer, subject, email)
                  values ('%2$s', '%1$s', 'dev', 'dev-g%3$s', 'g%3$s@example.invalid') on conflict do nothing;
                insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type, status,
                                                   can_view_attendance, can_view_academic, can_pay_fees, can_give_consent, can_receive_notices)
                  select '%2$s', '%1$s', '%4$s', 'GUARDIAN', '%5$s', %6$s, %6$s, %6$s, %6$s, true
                   where not exists (select 1 from guardian_relationship where guardian_person_id = '%1$s');
                """.formatted(id, TENANT, g[0], ZOYA, g[1], g[2]));
        }
    }

    @Test
    void announcementsReachTheRightPeopleOnceAndSmsIsLockScreenSafe() throws Exception {
        fixture();
        String section = ownerString("select md5('section-CBSE-K12-5-B')::uuid::text");
        String url = "/api/v1/campuses/" + NORTH + "/notices";

        // Whole-campus notices are office-only; parents cannot send at all.
        mvc.perform(json(post(url), "dev-ravi", "{\"title\":\"Sports day\",\"body\":\"Friday at 9\"}")).andExpect(status().isForbidden());
        mvc.perform(json(post(url), "dev-g711", "{\"title\":\"Hello\",\"body\":\"x\",\"sectionId\":\"" + section + "\"}")).andExpect(status().isNotFound());

        String body = "{\"title\":\"Sports day moved\",\"body\":\"Now on Friday at 9am.\",\"sectionId\":\"" + section + "\"}";
        String notice = JsonPath.read(mvc.perform(json(post(url), "dev-ravi", body)).andExpect(status().isCreated())
            .andExpect(jsonPath("$.people").value(2)).andReturn().getResponse().getContentAsString(), "$.id");

        // Active and notices-only guardians see it; the suspended guardian does not.
        mvc.perform(as(get("/api/v1/notices/mine"), "dev-g711")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.title=='Sports day moved')]", hasSize(1)));
        mvc.perform(as(get("/api/v1/notices/mine"), "dev-g712")).andExpect(jsonPath("$[?(@.title=='Sports day moved')]", hasSize(1)));
        mvc.perform(as(get("/api/v1/notices/mine"), "dev-g713")).andExpect(jsonPath("$", hasSize(0)));

        // Reading is personal.
        List<String> ids = JsonPath.read(mvc.perform(as(get("/api/v1/notices/mine"), "dev-g711")).andReturn().getResponse().getContentAsString(),
            "$[?(@.title=='Sports day moved')].id");
        String delivery = ids.get(0);
        mvc.perform(as(post("/api/v1/notices/" + delivery + "/read"), "dev-g712")).andExpect(status().isNotFound());
        mvc.perform(as(post("/api/v1/notices/" + delivery + "/read"), "dev-g711")).andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/notices/mine"), "dev-g711")).andExpect(jsonPath("$[?(@.id=='" + delivery + "')].read").value(hasItem(true)));

        // SMS goes out through the dispatcher (back-dated past any quiet hours) and names no child.
        ownerUpdate("update notice_delivery set send_after = now() - interval '1 minute' where channel = 'SMS' and status = 'PENDING'");
        TEXTS.clear();
        dispatcher.runOnce();
        assertTrue(TEXTS.stream().anyMatch(t -> t[0].equals("+919820000711") && t[1].startsWith("Schoomano: Sports day moved")), "sms to active guardian");
        assertTrue(TEXTS.stream().anyMatch(t -> t[0].equals("+919820000712")), "sms to notices-only guardian");
        assertFalse(TEXTS.stream().anyMatch(t -> t[0].equals("+919820000713")), "sms to suspended guardian");
        assertTrue(TEXTS.stream().allMatch(t -> t[1].length() <= 160));
        mvc.perform(as(get(url), "dev-asha")).andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id=='" + notice + "')].smsSent").value(hasItem(2)))
            .andExpect(jsonPath("$[?(@.id=='" + notice + "')].audience").value(hasItem(containsString("Grade 5"))));
        mvc.perform(as(get(url), "dev-meera")).andExpect(status().isNotFound());
    }

    @Test
    void absenceAndSubstitutionEventsBecomeNoticesForTheRightPeople() throws Exception {
        fixture();
        String section = ownerString("select md5('section-CBSE-K12-5-B')::uuid::text");
        int before = JsonPath.<List<?>>read(mvc.perform(as(get("/api/v1/notices/mine"), "dev-g712")).andReturn().getResponse().getContentAsString(), "$").size();

        // Ravi marks Zoya absent; the outbox event becomes an attendance notice for the guardian who may see attendance.
        mvc.perform(as(post("/api/v1/sections/" + section + "/attendance/batches"), "dev-ravi").header("Idempotency-Key", "key-NOTICE001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2026-10-06\",\"source\":\"WEB\",\"marks\":[{\"studentId\":\"" + ZOYA + "\",\"status\":\"ABSENT\",\"baseVersion\":0}]}"))
            .andExpect(status().isOk());
        // Events become notices when the dispatcher runs; after 21:00 India time their SMS wait for morning, so the
        // first pass creates them, then they are back-dated and the second pass sends them (any time of day).
        TEXTS.clear();
        dispatcher.runOnce();
        ownerUpdate("update notice_delivery set send_after = now() - interval '1 minute' where channel = 'SMS' and status = 'PENDING'");
        dispatcher.runOnce();
        mvc.perform(as(get("/api/v1/notices/mine"), "dev-g711")).andExpect(jsonPath("$[?(@.kind=='ATTENDANCE')].body").value(hasItem(containsString("Zoya was marked absent"))));
        // The notices-only guardian and the suspended one are not told.
        int after = JsonPath.<List<?>>read(mvc.perform(as(get("/api/v1/notices/mine"), "dev-g712")).andReturn().getResponse().getContentAsString(), "$").size();
        assertEquals(before, after);
        mvc.perform(as(get("/api/v1/notices/mine"), "dev-g713")).andExpect(jsonPath("$[?(@.kind=='ATTENDANCE')]", hasSize(0)));
        // SMS about attendance never names the child.
        assertTrue(TEXTS.stream().anyMatch(t -> t[0].equals("+919820000711")));
        assertTrue(TEXTS.stream().noneMatch(t -> t[1].contains("Zoya")));
        // Running again changes nothing.
        dispatcher.runOnce();
        mvc.perform(as(get("/api/v1/notices/mine"), "dev-g711")).andExpect(jsonPath("$[?(@.kind=='ATTENDANCE')]", hasSize(1)));
    }
}
