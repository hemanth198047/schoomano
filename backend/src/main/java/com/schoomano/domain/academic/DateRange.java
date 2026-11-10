package com.schoomano.domain.academic;

import java.time.LocalDate;
import java.util.Objects;

/** Inclusive range of calendar days, as used by academic years, terms and calendar events. */
public record DateRange(LocalDate startsOn, LocalDate endsOn) {
    public DateRange {
        Objects.requireNonNull(startsOn, "startsOn");
        Objects.requireNonNull(endsOn, "endsOn");
        if (endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("range ends before it starts: " + startsOn + " to " + endsOn);
        }
    }

    public boolean overlaps(DateRange other) {
        return !startsOn.isAfter(other.endsOn) && !other.startsOn.isAfter(endsOn);
    }

    public boolean contains(DateRange other) {
        return !other.startsOn.isBefore(startsOn) && !other.endsOn.isAfter(endsOn);
    }

    public DateRange plusYears(int years) {
        return new DateRange(startsOn.plusYears(years), endsOn.plusYears(years));
    }
}
