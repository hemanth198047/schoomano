package com.schoomano.domain.attendance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AttendanceFiguresTest {

    @Test
    void lateCountsAsAttendedAndRoundsToOneDecimal() {
        assertEquals(66.7, AttendanceFigures.percent(1, 1, 1));
        assertEquals(100.0, AttendanceFigures.percent(3, 2, 0));
        assertEquals(0.0, AttendanceFigures.percent(0, 0, 4));
    }

    @Test
    void noCountedDaysMeansNoFigure() {
        assertNull(AttendanceFigures.percent(0, 0, 0));
    }
}
