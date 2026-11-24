package com.schoomano;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** B9: a guardian sees only their own child's attendance, and only with the view-attendance capability. */
class StudentAttendanceApiIT extends ApiITSupport {

    static final String TENANT = "10000000-0000-4000-8000-000000000001";
    static final String HARI = "13000000-0000-4000-8000-000000000901";
    static final String URL = "/api/v1/students/" + HARI + "/attendance";

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String who) {
        return b.header("Authorization", dev(who));
    }

    private static void fixture() throws Exception {
        ownerUpdate("""
            insert into person (id, tenant_id, school_ref, given_name, family_name) values
              ('%2$s', '%1$s', 'STU-IT-901', 'Hari', 'Synthetic') on conflict (id) do nothing;
            insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id, programme_id, class_section_id)
              select '%1$s', '%2$s', '12000000-0000-4000-8000-000000000001', '15000000-0000-4000-8000-000000000001',
                     '14000000-0000-4000-8000-000000000001', md5('section-CBSE-K12-8-B')::uuid
               where not exists (select 1 from student_enrollment where student_person_id = '%2$s');
            insert into attendance_session (tenant_id, class_section_id, on_date)
              select '%1$s', md5('section-CBSE-K12-8-B')::uuid, d::date from unnest(array['2026-10-05','2026-10-06','2026-10-07']) d
              on conflict do nothing;
            insert into attendance_mark (tenant_id, session_id, student_person_id, status, marked_by, marked_at, source)
              select '%1$s', a.id, '%2$s', case a.on_date when '2026-10-05' then 'PRESENT' when '2026-10-06' then 'ABSENT' else 'LATE' end,
                     '%2$s', now(), 'WEB'
                from attendance_session a where a.class_section_id = md5('section-CBSE-K12-8-B')::uuid
              on conflict do nothing;
            """.formatted(TENANT, HARI));
        String[][] guardians = {{"911", "true"}, {"912", "false"}};
        for (String[] g : guardians) {
            String id = "13000000-0000-4000-8000-000000000" + g[0];
            ownerUpdate("""
                insert into person (id, tenant_id, school_ref, given_name, family_name, mobile_e164)
                  values ('%1$s', '%2$s', 'GRD-IT-%3$s', 'Parent%3$s', 'Synthetic', '+919840000%3$s') on conflict (id) do nothing;
                insert into identity (tenant_id, person_id, issuer, subject, email)
                  values ('%2$s', '%1$s', 'dev', 'dev-g%3$s', 'g%3$s@example.invalid') on conflict do nothing;
                insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type, status,
                                                   can_view_attendance, can_view_academic, can_pay_fees, can_give_consent, can_receive_notices)
                  select '%2$s', '%1$s', '%4$s', 'GUARDIAN', 'ACTIVE', %5$s, true, true, true, true
                   where not exists (select 1 from guardian_relationship where guardian_person_id = '%1$s');
                """.formatted(id, TENANT, g[0], HARI, g[1]));
        }
    }

    @Test
    void guardianSeesOwnChildOnlyAndWithTheCapability() throws Exception {
        fixture();
        String range = "?from=2026-10-01&to=2026-10-31";
        mvc.perform(as(get(URL + range), "dev-g911")).andExpect(status().isOk())
            .andExpect(jsonPath("$.days", hasSize(3)))
            .andExpect(jsonPath("$.present").value(1)).andExpect(jsonPath("$.absent").value(1)).andExpect(jsonPath("$.late").value(1))
            .andExpect(jsonPath("$.days[1].status").value("ABSENT")).andExpect(jsonPath("$.days[1].classLabel").exists());
        // A narrower range only counts what is inside it.
        mvc.perform(as(get(URL + "?from=2026-10-06&to=2026-10-06"), "dev-g911")).andExpect(jsonPath("$.days", hasSize(1)));
        // No capability: it looks like the child does not exist.
        mvc.perform(as(get(URL + range), "dev-g912")).andExpect(status().isNotFound());
        // Staff in scope can read it; a teacher is fine, a different campus is not.
        mvc.perform(as(get(URL + range), "dev-ravi")).andExpect(status().isOk());
        mvc.perform(as(get(URL + range), "dev-meera")).andExpect(status().isNotFound());
        // Ranges are bounded.
        mvc.perform(as(get(URL + "?from=2026-01-01&to=2026-12-31"), "dev-g911")).andExpect(status().isBadRequest());
        mvc.perform(as(get(URL + "?from=2026-10-31&to=2026-10-01"), "dev-g911")).andExpect(status().isBadRequest());
    }
}
