package com.schoomano.domain.timetable;

import java.time.DayOfWeek;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/** Plain-language text for the timetable problems the office has to fix before publishing. */
public final class ClashText {

    private ClashText() {
    }

    public static String dayName(int isoDay) {
        return DayOfWeek.of(isoDay).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /** @param kind "TEACHER" or "ROOM" */
    public static String describe(String kind, int isoDay, String periodLabel, String resourceName, List<String> sections) {
        String who = "TEACHER".equals(kind) ? resourceName + " is" : "Room " + resourceName + " is";
        return who + " booked for " + sections.size() + " classes at once on " + dayName(isoDay) + ", " + periodLabel
            + ": " + String.join(", ", sections) + ".";
    }

    /** The weekday a date falls on, as the timetable numbers it (Monday = 1). */
    public static int isoDay(java.time.LocalDate date) {
        return date.getDayOfWeek().getValue();
    }
}
