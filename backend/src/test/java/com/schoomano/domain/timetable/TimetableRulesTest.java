package com.schoomano.domain.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimetableRulesTest {

    private static BellSchedule.Slot slot(int n, String label, String from, String to, boolean brk) {
        return new BellSchedule.Slot(n, label, LocalTime.parse(from), LocalTime.parse(to), brk);
    }

    @Test
    void aNormalDayIsAccepted() {
        assertEquals(List.of(), BellSchedule.problems(List.of(
            slot(1, "Period 1", "08:30", "09:15", false), slot(2, "Break", "09:15", "09:30", true),
            slot(3, "Period 2", "09:30", "10:15", false))));
    }

    @Test
    void emptyAndOversizedDaysAreRefused() {
        assertEquals(1, BellSchedule.problems(List.of()).size());
        var many = new java.util.ArrayList<BellSchedule.Slot>();
        for (int i = 1; i <= 13; i++) {
            many.add(slot(i, "P" + i, String.format("%02d:00", 6 + i / 2), String.format("%02d:30", 6 + i / 2), false));
        }
        assertTrue(BellSchedule.problems(many).stream().anyMatch(p -> p.contains("at most 12")));
    }

    @Test
    void overlapsAreNamed() {
        var p = BellSchedule.problems(List.of(slot(1, "Period 1", "08:30", "09:30", false), slot(2, "Period 2", "09:15", "10:00", false)));
        assertEquals(1, p.size());
        assertTrue(p.get(0).contains("Period 1") && p.get(0).contains("overlaps Period 2"));
    }

    @Test
    void numbersMustFollowTheClockAndBeUnique() {
        assertTrue(BellSchedule.problems(List.of(slot(2, "First", "08:30", "09:15", false), slot(1, "Second", "09:15", "10:00", false)))
            .stream().anyMatch(p -> p.contains("numbered before")));
        assertTrue(BellSchedule.problems(List.of(slot(1, "A", "08:30", "09:15", false), slot(1, "B", "09:15", "10:00", false)))
            .stream().anyMatch(p -> p.contains("share the number 1")));
    }

    @Test
    void endBeforeStartAndAllBreaksAreRefused() {
        assertTrue(BellSchedule.problems(List.of(slot(1, "P1", "09:00", "08:00", false))).get(0).contains("end after it starts"));
        assertTrue(BellSchedule.problems(List.of(slot(1, "Lunch", "12:00", "12:30", true))).get(0).contains("lesson period"));
    }

    @Test
    void clashTextIsPlainAndDatesMapToTimetableDays() {
        assertEquals("Ravi Teacher is booked for 2 classes at once on Monday, Period 1: Grade 5 A, Grade 5 B.",
            ClashText.describe("TEACHER", 1, "Period 1", "Ravi Teacher", List.of("Grade 5 A", "Grade 5 B")));
        assertEquals("Room R101 is booked for 2 classes at once on Saturday, Period 2: Grade 5 A, Grade 6 A.",
            ClashText.describe("ROOM", 6, "Period 2", "R101", List.of("Grade 5 A", "Grade 6 A")));
        assertEquals(1, ClashText.isoDay(LocalDate.parse("2026-11-23")));
        assertEquals(7, ClashText.isoDay(LocalDate.parse("2026-11-29")));
    }
}
