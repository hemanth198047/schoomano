package com.schoomano.domain.attendance;

/**
 * Attendance percentage for a stretch of days. A child who arrived late was at school, so late counts as attended.
 * Approved leave is left out of the days that count, so it neither helps nor hurts. With no counted days there is no
 * percentage (null), never 0 or 100.
 */
public final class AttendanceFigures {

    private AttendanceFigures() {
    }

    public static Double percent(int present, int late, int absent) {
        int counted = present + late + absent;
        if (counted == 0) {
            return null;
        }
        return Math.round((present + late) * 1000.0 / counted) / 10.0;
    }
}
