package com.schoomano.domain.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttendanceRulesTest {

    private static final UUID SECTION = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID B = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    @Test
    void statusesParseLeniently() {
        assertEquals(AttendanceStatus.LATE, AttendanceStatus.parse(" late ").orElseThrow());
        assertTrue(AttendanceStatus.parse("holiday").isEmpty());
        assertTrue(AttendanceStatus.parse(null).isEmpty());
    }

    @Test
    void onlyAbsenceNotifiesGuardians() {
        assertTrue(AttendanceStatus.ABSENT.notifiesGuardians());
        assertFalse(AttendanceStatus.LATE.notifiesGuardians());
        assertFalse(AttendanceStatus.LEAVE.notifiesGuardians());
    }

    @Test
    void fingerprintIgnoresRowOrderButNotContent() {
        var one = new BatchFingerprint.Row(A, AttendanceStatus.PRESENT, 0);
        var two = new BatchFingerprint.Row(B, AttendanceStatus.ABSENT, 1);
        assertEquals(BatchFingerprint.of(SECTION, DAY, List.of(one, two)), BatchFingerprint.of(SECTION, DAY, List.of(two, one)));
        var changed = new BatchFingerprint.Row(B, AttendanceStatus.LATE, 1);
        assertNotEquals(BatchFingerprint.of(SECTION, DAY, List.of(one, two)), BatchFingerprint.of(SECTION, DAY, List.of(one, changed)));
        assertNotEquals(BatchFingerprint.of(SECTION, DAY, List.of(one)), BatchFingerprint.of(SECTION, DAY.plusDays(1), List.of(one)));
    }

    @Test
    void duplicateStudentsAreFound() {
        var one = new BatchFingerprint.Row(A, AttendanceStatus.PRESENT, 0);
        var again = new BatchFingerprint.Row(A, AttendanceStatus.ABSENT, 0);
        assertEquals(List.of(A), BatchFingerprint.duplicates(List.of(one, again)));
        assertTrue(BatchFingerprint.duplicates(List.of(one)).isEmpty());
    }
}
