package com.schoomano;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** B2 acceptance: dry run, commit, repeatable re-import, three-way conflicts, stale preview, rollback, speed. */
class ImportApiIT extends ApiITSupport {

    static final String NORTH_YEAR = "15000000-0000-4000-8000-000000000001";

    private ResultActions upload(String who, String name, String csv) throws Exception {
        return mvc.perform(multipart("/api/v1/academic-years/" + NORTH_YEAR + "/imports/students")
            .file(new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", dev(who)));
    }

    private String validate(String csv) throws Exception {
        return JsonPath.read(upload("dev-asha", "students.csv", csv).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private ResultActions commit(String id) throws Exception {
        return mvc.perform(post("/api/v1/imports/" + id + "/commit").header("Authorization", dev("dev-asha")));
    }

    static final String FIRST = """
        Admission No,First Name,Last Name,DOB,Programme,Grade,Section
        IMP-1,Kavya,Iyer,14/08/2015,CBSE-K12,8,A
        IMP-2,Arjun,Nair,02/01/2014,CBSE-K12,G9,B
        IMP-3,Diya,Shah,,CBSE-K12,Grade 8,B
        IMP-4,,Missing,,,,
        STU-0020,Anaya,Duplicate,,,,
        """;

    @Test
    void fullCoexistenceCycle() throws Exception {
        long people = ownerCount("select count(*) from person");

        // 1. Dry run: rows are classified and nothing is written to student records.
        String first = JsonPath.read(upload("dev-asha", "students.csv", FIRST).andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("VALIDATED"))
            .andExpect(jsonPath("$.counts.CREATE").value(3))
            .andExpect(jsonPath("$.counts.ERROR").value(1))
            .andExpect(jsonPath("$.counts.CONFLICT").value(1))
            .andExpect(jsonPath("$.rows[0].action").value("ERROR"))
            .andExpect(jsonPath("$.rows[0].problems[0]").value("First name is missing."))
            .andReturn().getResponse().getContentAsString(), "$.id");
        assertEquals(people, ownerCount("select count(*) from person"), "a dry run writes no student");

        // 2. Commit applies exactly the dry run.
        commit(first).andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(3))
            .andExpect(jsonPath("$.batch.status").value("COMMITTED"));
        assertEquals("CBSE-K12/G8/A", ownerString("""
            select p.code || '/' || g.code || '/' || s.code from person x
              join student_enrollment e on e.student_person_id = x.id
              join class_section s on s.id = e.class_section_id join grade_level g on g.id = s.grade_level_id
              join programme p on p.id = g.programme_id where x.school_ref = 'IMP-1'"""));
        assertEquals("LEGACY", ownerString("select provenance->>'source' from person where school_ref = 'IMP-2'"));
        commit(first).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_validated"));

        // 3. The same file again changes nothing.
        String again = validate(FIRST);
        mvc.perform(get("/api/v1/imports/" + again).header("Authorization", dev("dev-asha")))
            .andExpect(jsonPath("$.counts.UNCHANGED").value(3))
            .andExpect(jsonPath("$.counts.CREATE").value(0));
        commit(again).andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.updated").value(0));

        // 4. Schoomano edits Arjun's and Diya's last names (as the office would in the UI).
        ownerUpdate("update person set family_name = 'Nair-Kumar' where school_ref in ('IMP-2', 'IMP-3')");
        // The old system moved Kavya to section B, left Arjun's name alone, and changed Diya's name too.
        String changed = FIRST.replace("IMP-1,Kavya,Iyer,14/08/2015,CBSE-K12,8,A", "IMP-1,Kavya,Iyer,14/08/2015,CBSE-K12,8,B")
            .replace("IMP-3,Diya,Shah,", "IMP-3,Diya,Shah-Patel,");
        String third = validate(changed);
        mvc.perform(get("/api/v1/imports/" + third).header("Authorization", dev("dev-asha")).param("action", "CONFLICT"))
            .andExpect(jsonPath("$.rowsTotal").value(2))   // Diya (both changed) + Anaya (unlinked)
            .andExpect(jsonPath("$.rows[?(@.externalRef=='IMP-3')].problems[0]").value(
                org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.startsWith("Last name was changed in Schoomano"))));
        mvc.perform(get("/api/v1/imports/" + third).header("Authorization", dev("dev-asha")).param("action", "UPDATE"))
            .andExpect(jsonPath("$.rows[0].externalRef").value("IMP-1"))
            .andExpect(jsonPath("$.rows[0].changes[0].after").value("CBSE-K12/G8/B"));
        commit(third).andExpect(jsonPath("$.updated").value(1));
        assertEquals("Nair-Kumar", ownerString("select family_name from person where school_ref = 'IMP-2'"),
            "Schoomano's edit is kept when the old system did not change the field");
        assertEquals("Nair-Kumar", ownerString("select family_name from person where school_ref = 'IMP-3'"),
            "a conflict is never overwritten");

        // 5. Rolling back an earlier batch is refused; the latest one reverts Kavya's move.
        mvc.perform(post("/api/v1/imports/" + first + "/rollback").header("Authorization", dev("dev-asha")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_latest"));
        mvc.perform(post("/api/v1/imports/" + third + "/rollback").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.restored").value(1))
            .andExpect(jsonPath("$.batch.status").value("ROLLED_BACK"));
        assertTrue(ownerString("""
            select s.code from person x join student_enrollment e on e.student_person_id = x.id
              join class_section s on s.id = e.class_section_id where x.school_ref = 'IMP-1'""").equals("A"));
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'import.rolled_back'"));
    }

    @Test
    void commitIsRefusedWhenRecordsChangedAfterTheDryRun() throws Exception {
        String csv = "admission_no,given_name\nSTALE-1,Ira\n";
        commit(validate(csv)).andExpect(status().isOk());
        String next = validate("admission_no,given_name\nSTALE-1,Iravati\n");
        ownerUpdate("update person set given_name = 'Ira M' where school_ref = 'STALE-1'");
        commit(next).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("stale_preview"));
    }

    @Test
    void onlyImportManagersCanImportAndOtherTenantsCannotSeeBatches() throws Exception {
        upload("dev-ravi", "s.csv", "admission_no,given_name\nX-1,A\n").andExpect(status().isForbidden());
        upload("dev-meera", "s.csv", "admission_no,given_name\nX-1,A\n").andExpect(status().isNotFound());
        String id = validate("admission_no,given_name\nVIS-1,Tara\n");
        mvc.perform(get("/api/v1/imports/" + id).header("Authorization", dev("dev-other"))).andExpect(status().isNotFound());
        upload("dev-asha", "s.xls", "not really").andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("unreadable_file"));
    }

    @Test
    void fiveThousandStudentsImportWellUnderAMinute() throws Exception {
        StringBuilder csv = new StringBuilder("admission_no,given_name,family_name,dob\n");
        for (int i = 0; i < 5000; i++) {
            csv.append("PERF-").append(i).append(",Student").append(i).append(",Synthetic,01/06/2014\n");
        }
        long start = System.nanoTime();
        String id = validate(csv.toString());
        commit(id).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(5000));
        double seconds = (System.nanoTime() - start) / 1e9;
        assertTrue(seconds < 60, "5,000 students took " + seconds + " s");
        System.out.printf("B2 performance: validate + commit of 5,000 students in %.1f s%n", seconds);
        // Re-import of the unchanged file is all no-ops.
        String again = validate(csv.toString());
        mvc.perform(get("/api/v1/imports/" + again).header("Authorization", dev("dev-asha")))
            .andExpect(jsonPath("$.counts.UNCHANGED").value(5000));
    }
}
