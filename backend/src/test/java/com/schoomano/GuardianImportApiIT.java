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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** B38: guardians named in the student import are linked once, never revived once ended, and rolled back with the import. */
class GuardianImportApiIT extends ApiITSupport {

    static final String NORTH_YEAR = "15000000-0000-4000-8000-000000000001";

    private ResultActions upload(String csv) throws Exception {
        return mvc.perform(multipart("/api/v1/academic-years/" + NORTH_YEAR + "/imports/students")
            .file(new MockMultipartFile("file", "students.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
            .header("Authorization", dev("dev-asha")));
    }

    static final String FILE = """
        Admission No,First Name,Last Name,Programme,Grade,Section,Father Name,Father Mobile,Mother Name,Mother Mobile
        GI-1,Kavya,Iyer,CBSE-K12,8,A,Suresh Iyer,98000 00001,Lakshmi Iyer,9800000002
        GI-2,Arjun,Iyer,CBSE-K12,8,A,Suresh Iyer,9800000001,,
        GI-3,Diya,Shah,CBSE-K12,8,A,,,,
        GI-4,Bad,Row,CBSE-K12,8,A,Someone,123,,
        """;

    @Test
    void guardiansAreLinkedOnceNeverRevivedAndRolledBackWithTheImport() throws Exception {
        // The preview shows each guardian that would be added, and a bad mobile stops only its row.
        String preview = upload(FILE).andExpect(status().isCreated()).andExpect(jsonPath("$.counts.CREATE").value(3))
            .andExpect(jsonPath("$.counts.ERROR").value(1)).andReturn().getResponse().getContentAsString();
        assertTrue(preview.contains("Father: Suresh Iyer, +919800000001"));
        assertTrue(preview.contains("not a valid Indian mobile"));
        assertEquals(0, ownerCount("select count(*) from person where mobile_e164 in ('+919800000001', '+919800000002')"), "a dry run writes nothing");

        // Commit: two new contacts (the father of both children is one person) and three links.
        String batch = JsonPath.read(preview, "$.id");
        mvc.perform(post("/api/v1/imports/" + batch + "/commit").header("Authorization", dev("dev-asha"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.created").value(3));
        assertEquals(2, ownerCount("select count(*) from person where mobile_e164 in ('+919800000001', '+919800000002')"));
        assertEquals(3, ownerCount("""
            select count(*) from guardian_relationship r join person s on s.id = r.student_person_id
             where s.school_ref in ('GI-1', 'GI-2', 'GI-3')"""));
        assertEquals("FATHER", ownerString("""
            select r.relationship_type from guardian_relationship r join person s on s.id = r.student_person_id
              join person g on g.id = r.guardian_person_id where s.school_ref = 'GI-1' and g.mobile_e164 = '+919800000001'"""));
        assertEquals("LEGACY", ownerString("select provenance->>'source' from person where mobile_e164 = '+919800000002'"));

        // The same file again changes nothing, and a relationship that was ended is not revived.
        upload(FILE).andExpect(status().isCreated()).andExpect(jsonPath("$.counts.UNCHANGED").value(3)).andExpect(jsonPath("$.counts.CREATE").value(0));
        ownerUpdate("""
            update guardian_relationship set status = 'ENDED', ended_at = now(), valid_to = now()
             where student_person_id = (select id from person where school_ref = 'GI-2')""");
        upload(FILE).andExpect(status().isCreated()).andExpect(jsonPath("$.counts.UNCHANGED").value(3));
        assertEquals(0, ownerCount("""
            select count(*) from guardian_relationship r join person s on s.id = r.student_person_id
             where s.school_ref = 'GI-2' and r.status <> 'ENDED'"""));

        // Rollback removes what the import made and keeps what has since changed, saying why.
        String result = mvc.perform(post("/api/v1/imports/" + batch + "/rollback").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.removed").value(2)).andReturn().getResponse().getContentAsString();
        List<String> kept = JsonPath.read(result, "$.kept");
        assertTrue(kept.stream().anyMatch(k -> k.startsWith("GI-2")));
        assertEquals(0, ownerCount("select count(*) from person where school_ref in ('GI-1', 'GI-3')"));
        assertEquals(0, ownerCount("select count(*) from person where mobile_e164 = '+919800000002'"), "a contact made by the import and unused is removed");
        assertEquals(1, ownerCount("select count(*) from person where mobile_e164 = '+919800000001'"), "a contact still linked elsewhere stays");
        mvc.perform(get("/api/v1/imports/" + batch).header("Authorization", dev("dev-asha"))).andExpect(jsonPath("$.status").value("ROLLED_BACK"));

        // Leave nothing behind for other tests.
        ownerUpdate("""
            delete from guardian_relationship where student_person_id in (select id from person where school_ref = 'GI-2');
            delete from student_enrollment where student_person_id in (select id from person where school_ref = 'GI-2');
            delete from external_link where person_id in (select id from person where school_ref = 'GI-2');
            delete from person where school_ref = 'GI-2' or mobile_e164 = '+919800000001';""");
    }
}
