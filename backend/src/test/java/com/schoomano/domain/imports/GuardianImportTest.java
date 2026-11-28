package com.schoomano.domain.imports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.schoomano.domain.imports.StudentImport.Action;
import com.schoomano.domain.imports.StudentImport.Contact;
import com.schoomano.domain.imports.StudentImport.GuardianBook;
import com.schoomano.domain.imports.StudentImport.Linked;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** B38: father, mother and guardian columns add guardians, never change or revive a relationship. */
class GuardianImportTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
    static final String HEAD = "Adm No,First Name,Father Name,Father Mobile,Mother's Name,Mother Mobile,Guardian Name,Guardian Mobile,Guardian Email\n";
    static final UUID STUDENT = UUID.fromString("20000000-0000-4000-8000-000000000001");
    static final UUID KNOWN = UUID.fromString("30000000-0000-4000-8000-000000000001");

    static StudentImport.Plan plan(String csv, Map<String, Linked> linked, GuardianBook book) {
        return StudentImport.plan(Csv.parse(csv), List.of(), linked, Set.of(), TODAY, book);
    }

    static Map<String, String> same() {
        return Map.of("givenName", "Asha", "familyName", "", "dateOfBirth", "", "placement", "");
    }

    @Test
    void newStudentGetsEachGuardianNamedInTheFile() {
        var row = plan(HEAD + "A-1,Asha,Ramesh Kumar,98765 43210,Priya Kumar,+91 91234 56789,,,\n", Map.of(), GuardianBook.EMPTY).rows().get(0);
        assertEquals(Action.CREATE, row.action());
        assertEquals(2, row.guardians().size());
        assertEquals("FATHER", row.guardians().get(0).relationship());
        assertEquals("+919876543210", row.guardians().get(0).mobile());
        assertEquals("MOTHER", row.guardians().get(1).relationship());
        assertTrue(row.changes().stream().anyMatch(c -> c.field().equals("guardian.father") && c.after().contains("Ramesh Kumar")));
    }

    @Test
    void aMobileThatAlreadyBelongsToSomeoneIsLinkedNotDuplicated() {
        var book = new GuardianBook(Map.of("+919876543210", new Contact(KNOWN, "R Kumar")), Set.of());
        var row = plan(HEAD + "A-1,Asha,,9876543210,,,,,\n", Map.of(), book).rows().get(0);
        assertEquals(Action.CREATE, row.action());
        assertEquals(KNOWN, row.guardians().get(0).existingPersonId());
        assertTrue(row.changes().stream().anyMatch(c -> c.after().contains("existing contact")));
    }

    @Test
    void anExistingStudentWithOnlyNewGuardiansIsAnUpdateAndOnceLinkedItIsUnchanged() {
        var linked = Map.of("A-1", new Linked(STUDENT, same(), same()));
        var first = plan(HEAD + "A-1,Asha,Ramesh Kumar,9876543210,,,,,\n", linked, GuardianBook.EMPTY).rows().get(0);
        assertEquals(Action.UPDATE, first.action());
        var book = new GuardianBook(Map.of("+919876543210", new Contact(KNOWN, "Ramesh Kumar")), Set.of(GuardianBook.pair(STUDENT, KNOWN)));
        var again = plan(HEAD + "A-1,Asha,Ramesh Kumar,9876543210,,,,,\n", linked, book).rows().get(0);
        assertEquals(Action.UNCHANGED, again.action());
        assertTrue(again.guardians().isEmpty());
    }

    @Test
    void aRelationshipInAnyStatusIsNeverRevivedOrChanged() {
        // The pair exists (it may be ended or restricted); the file naming that guardian again adds nothing.
        var linked = Map.of("A-1", new Linked(STUDENT, same(), same()));
        var book = new GuardianBook(Map.of("+919876543210", new Contact(KNOWN, "Ramesh Kumar")), Set.of(GuardianBook.pair(STUDENT, KNOWN)));
        assertEquals(Action.UNCHANGED, plan(HEAD + "A-1,Asha,Someone Else,9876543210,,,,,\n", linked, book).rows().get(0).action());
    }

    @Test
    void badGuardianDetailsAreRowProblemsAndStopTheRow() {
        var rows = plan(HEAD
            + "A-1,Asha,Ramesh,12345,,,,,\n"
            + "A-2,Ravi,Ramesh,9876543210,Priya,9876543210,,,\n"
            + "A-3,Nisha,,,,,Uncle,9876543211,not-an-email\n"
            + "A-4,Farah,,9876543212,,,,,\n"
            + "A-5,Meera,Ramesh,,,,,,\n", Map.of(), GuardianBook.EMPTY).rows();
        assertEquals(Action.ERROR, rows.get(0).action());
        assertTrue(rows.get(0).problems().get(0).contains("not a valid Indian mobile"));
        assertTrue(rows.get(1).problems().get(0).contains("same mobile number"));
        assertTrue(rows.get(2).problems().get(0).contains("email"));
        assertTrue(rows.get(3).problems().get(0).contains("name is missing"));
        assertTrue(rows.get(4).problems().get(0).contains("mobile number is missing"));
    }

    @Test
    void aGuardianMobileThatIsTheStudentsOwnIsRefused() {
        var linked = Map.of("A-1", new Linked(STUDENT, same(), same()));
        var book = new GuardianBook(Map.of("+919876543210", new Contact(STUDENT, "Asha")), Set.of());
        assertTrue(plan(HEAD + "A-1,Asha,Ramesh,9876543210,,,,,\n", linked, book).rows().get(0).problems().get(0).contains("belongs to the student"));
    }

    @Test
    void aFileWithoutGuardianColumnsPlansAsBefore() {
        var row = StudentImport.plan(Csv.parse("Adm No,First Name\nA-1,Asha\n"), List.of(), Map.of(), Set.of(), TODAY).rows().get(0);
        assertEquals(Action.CREATE, row.action());
        assertTrue(row.guardians().isEmpty());
    }

    @Test
    void theRelationshipColumnSaysWhatTheGuardianColumnsStandFor() {
        String head = "Adm No,First Name,Guardian Name,Guardian Mobile,Relationship\n";
        var rows = plan(head
            + "A-1,Asha,Lata Rao,9876543210,Grandmother\n"
            + "A-2,Ravi,Om Rao,9876543211,uncle\n"
            + "A-3,Mina,Sam Rao,9876543212,\n"
            + "A-4,Tara,Kim Rao,9876543213,Friend\n", Map.of(), GuardianBook.EMPTY).rows();
        assertEquals("GRANDPARENT", rows.get(0).guardians().get(0).relationship());
        assertTrue(rows.get(0).changes().stream().anyMatch(c -> c.after().startsWith("Grandparent: Lata Rao")));
        assertEquals("OTHER", rows.get(1).guardians().get(0).relationship());
        assertEquals("GUARDIAN", rows.get(2).guardians().get(0).relationship(), "an empty cell means plain guardian");
        assertEquals(Action.ERROR, rows.get(3).action());
        assertTrue(rows.get(3).problems().get(0).contains("Friend"));
    }

    @Test
    void theRelationshipColumnDoesNotChangeFatherAndMotherColumns() {
        var row = plan("Adm No,First Name,Father Name,Father Mobile,Relationship\nA-1,Asha,Ramesh Kumar,9876543210,mother\n", Map.of(), GuardianBook.EMPTY)
            .rows().get(0);
        assertEquals("FATHER", row.guardians().get(0).relationship());
    }
}
