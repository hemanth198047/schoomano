package com.schoomano.domain.attendance;

import java.util.Optional;

/** What a teacher can record for a student on a day. */
public enum AttendanceStatus {
    PRESENT, ABSENT, LATE, LEAVE;

    public static Optional<AttendanceStatus> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(text.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Guardians with attendance permission are told when a child is marked absent (delivered by the notices slice). */
    public boolean notifiesGuardians() {
        return this == ABSENT;
    }
}
