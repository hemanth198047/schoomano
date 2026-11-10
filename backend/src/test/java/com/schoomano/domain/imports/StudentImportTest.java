package com.schoomano.domain.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.schoomano.domain.imports.StudentImport.Action;
import com.schoomano.domain.imports.StudentImport.Linked;
import com.schoomano.domain.imports.StudentImport.Placement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StudentImportTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    static final List<Placement> SECTIONS = List.of(
        new Placement("CBSE-K12", "G5", "Grade 5", 5, "A"), new Placement("CBSE-K12", "G5", "Grade 5", 5, "B"),
        new Placement("CBSE-K12", "G6", "Grade 6", 6, "A"));

    static StudentImport.Plan plan(String csv, Map<String, Linked> linked) {
        return StudentImport.plan(Csv.parse(csv), SECTIONS, linked, Set.of(), TODAY);
    }

    static Map<String, String> values(String given, String family, String dob, String placement) {
        return Map.of("givenName", given, "familyName", family, "dateOfBirth", dob, "placement", placement);
    }

    @Nested
    class Parsing {
        @Test
        void quotedFieldsBomAndCrlfAreHandled() {
            var rows = Csv.parse("﻿a,b\r\n\"x, y\",\"say \"\"hi\"\"\"\r\n\r\n\"multi\nline\",z");
            assertEquals(List.of(List.of("a", "b"), List.of("x, y", "say \"hi\""), List.of("multi\nline", "z")), rows);
        }

        @Test
        void unterminatedQuoteIsReported() {
            assertThrows(IllegalArgumentException.class, () -> Csv.parse("a\n\"open"));
        }

        @Test
        void indianDateFormatsAreAccepted() {
            assertEquals(LocalDate.of(2016, 3, 7), StudentImport.parseDate("07/03/2016"));
            assertEquals(LocalDate.of(2016, 3, 7), StudentImport.parseDate("7-3-2016"));
            assertEquals(LocalDate.of(2016, 3, 7), StudentImport.parseDate("2016-03-07"));
            assertEquals(null, StudentImport.parseDate("31/02/2016"));
            assertEquals(LocalDate.of(2016, 3, 7), StudentImport.parseDate("42436"), "Excel serial date");
        }
    }

    @Nested
    class Validation {
        @Test
        void headerAliasesAndGradeWrittenAsANumberAreUnderstood() {
            var p = plan("Adm No,First Name,Surname,DOB,Board,Class,Division\nA-1,Asha,Rao,01/02/2016,cbse-k12,5,b\n", Map.of());
            assertEquals(Action.CREATE, p.rows().get(0).action());
            assertEquals("CBSE-K12/G5/B", p.rows().get(0).incoming().get("placement"));
        }

        @Test
        void missingRequiredColumnStopsTheWholeFile() {
            var p = plan("Name,Class\nAsha,5\n", Map.of());
            assertTrue(p.rows().isEmpty());
            assertTrue(p.fileProblems().get(0).contains("admission number"));
        }

        @Test
        void rowErrorsNameTheProblemAndDuplicatesAreFlaggedOnBothRows() {
            var p = plan("""
                admission_no,given_name,date_of_birth,programme,grade,section
                A1,Asha,2030-01-01,,,
                A1,Ravi,,CBSE-K12,G9,A
                ,Meera,,,,
                """, Map.of());
            assertEquals(List.of(Action.ERROR, Action.ERROR, Action.ERROR), p.rows().stream().map(r -> r.action()).toList());
            assertTrue(p.rows().get(0).problems().stream().anyMatch(s -> s.contains("not plausible")));
            assertTrue(p.rows().get(1).problems().stream().anyMatch(s -> s.contains("No section A")));
            assertTrue(p.rows().get(1).problems().stream().anyMatch(s -> s.contains("more than once")));
            assertEquals(4, p.rows().get(2).rowNo());
        }

        @Test
        void existingUnlinkedStudentIsAConflictNotACreate() {
            var p = StudentImport.plan(Csv.parse("admission_no,given_name\nA1,Asha\n"), SECTIONS, Map.of(), Set.of("A1"), TODAY);
            assertEquals(Action.CONFLICT, p.rows().get(0).action());
        }
    }

    @Nested
    class ThreeWay {
        final UUID id = UUID.randomUUID();
        final String file = "admission_no,given_name,family_name,dob,programme,grade,section\nA1,Asha,Rao,2016-02-01,CBSE-K12,G5,A\n";

        @Test
        void sameFileAgainChangesNothing() {
            var v = values("Asha", "Rao", "2016-02-01", "CBSE-K12/G5/A");
            assertEquals(Action.UNCHANGED, plan(file, Map.of("A1", new Linked(id, v, v))).rows().get(0).action());
        }

        @Test
        void oldSystemChangeIsApplied() {
            var last = values("Asha", "Rao", "2016-02-01", "CBSE-K12/G5/B");
            var r = plan(file, Map.of("A1", new Linked(id, last, last))).rows().get(0);
            assertEquals(Action.UPDATE, r.action());
            assertEquals(List.of(new StudentImport.FieldChange("placement", "CBSE-K12/G5/B", "CBSE-K12/G5/A")), r.changes());
        }

        @Test
        void schoomanoEditIsKeptWhenTheOldSystemDidNotChangeTheField() {
            var last = values("Asha", "Rao", "2016-02-01", "CBSE-K12/G5/A");
            var current = values("Asha", "Rao-Menon", "2016-02-01", "CBSE-K12/G5/A");
            assertEquals(Action.UNCHANGED, plan(file, Map.of("A1", new Linked(id, last, current))).rows().get(0).action());
        }

        @Test
        void bothSidesChangedIsAConflictAndNothingIsApplied() {
            var last = values("Asha", "R", "2016-02-01", "CBSE-K12/G5/B");
            var current = values("Asha", "Rao-Menon", "2016-02-01", "CBSE-K12/G5/B");
            var r = plan(file, Map.of("A1", new Linked(id, last, current))).rows().get(0);
            assertEquals(Action.CONFLICT, r.action());
            assertTrue(r.changes().isEmpty(), "a conflicting row applies nothing, not even its other changes");
            assertTrue(r.problems().get(0).startsWith("Last name"));
        }

        @Test
        void linkedStudentsMissingFromTheFileAreListedNotDeleted() {
            var v = values("Ravi", "", "", "");
            var p = plan(file, Map.of("B7", new Linked(UUID.randomUUID(), v, v)));
            assertEquals(List.of("B7"), p.missingFromFile());
            assertEquals(Map.of(Action.CREATE, 1), Map.of(Action.CREATE, p.counts().get(Action.CREATE)));
        }
    }
}
