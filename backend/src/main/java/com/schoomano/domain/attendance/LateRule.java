package com.schoomano.domain.attendance;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Turns a PRESENT mark into LATE when the child was marked in after the school day's start plus the allowed minutes.
 * Applies only to today's marks: an entry made later for an earlier day says nothing about when the child arrived.
 * Pure: the caller supplies the configured start and minutes, so nothing happens when either is not configured.
 */
public final class LateRule {

    private LateRule() {
    }

    public static AttendanceStatus apply(AttendanceStatus requested, LocalDate day, ZonedDateTime markedAt, ZoneId zone,
                                         LocalTime dayStart, Integer lateAfterMinutes) {
        if (requested != AttendanceStatus.PRESENT || dayStart == null || lateAfterMinutes == null) {
            return requested;
        }
        ZonedDateTime local = markedAt.withZoneSameInstant(zone);
        if (!local.toLocalDate().equals(day)) {
            return requested;
        }
        ZonedDateTime limit = day.atTime(dayStart).atZone(zone).plus(Duration.ofMinutes(lateAfterMinutes));
        return local.isAfter(limit) ? AttendanceStatus.LATE : requested;
    }
}
