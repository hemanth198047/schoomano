package com.schoomano.domain.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class LateRuleTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final LocalDate DAY = LocalDate.of(2026, 10, 5);
    static final LocalTime START = LocalTime.of(8, 30);

    private static ZonedDateTime at(int h, int m, int s) {
        return ZonedDateTime.of(DAY, LocalTime.of(h, m, s), IST);
    }

    private static AttendanceStatus rule(AttendanceStatus s, ZonedDateTime when, LocalTime start, Integer minutes) {
        return LateRule.apply(s, DAY, when, IST, start, minutes);
    }

    @Test
    void presentAfterTheAllowedMinutesBecomesLateAndOnTheMinuteDoesNot() {
        assertEquals(AttendanceStatus.PRESENT, rule(AttendanceStatus.PRESENT, at(8, 45, 0), START, 15));
        assertEquals(AttendanceStatus.LATE, rule(AttendanceStatus.PRESENT, at(8, 45, 1), START, 15));
        assertEquals(AttendanceStatus.PRESENT, rule(AttendanceStatus.PRESENT, at(8, 10, 0), START, 15));
    }

    @Test
    void onlyPresentChangesAndNothingHappensWithoutBothSettings() {
        assertEquals(AttendanceStatus.ABSENT, rule(AttendanceStatus.ABSENT, at(11, 0, 0), START, 15));
        assertEquals(AttendanceStatus.LEAVE, rule(AttendanceStatus.LEAVE, at(11, 0, 0), START, 15));
        assertEquals(AttendanceStatus.PRESENT, rule(AttendanceStatus.PRESENT, at(11, 0, 0), null, 15));
        assertEquals(AttendanceStatus.PRESENT, rule(AttendanceStatus.PRESENT, at(11, 0, 0), START, null));
    }

    @Test
    void anEntryMadeOnAnotherDayIsLeftAlone() {
        ZonedDateTime nextDay = at(10, 0, 0).plusDays(1);
        assertEquals(AttendanceStatus.PRESENT, rule(AttendanceStatus.PRESENT, nextDay, START, 15));
    }

    @Test
    void theSchoolsClockDecidesNotUtc() {
        // 03:20 UTC on the day is 08:50 in India: late by 20 minutes.
        ZonedDateTime utc = ZonedDateTime.of(DAY, LocalTime.of(3, 20), ZoneId.of("UTC"));
        assertEquals(AttendanceStatus.LATE, rule(AttendanceStatus.PRESENT, utc, START, 15));
    }
}
