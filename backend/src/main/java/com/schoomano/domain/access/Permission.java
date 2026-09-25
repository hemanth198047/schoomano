package com.schoomano.domain.access;

/**
 * Code-defined permissions. Roles are tenant-configurable bundles of these; permissions are not,
 * because each one is enforced by server code and covered by tests.
 */
public enum Permission {
    CAMPUS_READ(false),
    CAMPUS_MANAGE(true),
    PERSON_MANAGE(true),
    ROLE_GRANT_MANAGE(true),
    STUDENT_READ_BASIC(false),
    STUDENT_READ_ACADEMIC(false),
    STUDENT_WRITE(true),
    ATTENDANCE_READ(false),
    ATTENDANCE_MARK(false),
    ATTENDANCE_CORRECT(true),
    FEE_READ(false),
    FEE_PAY(true),
    FEE_MANAGE(true),
    CONSENT_GIVE(true),
    NOTICE_RECEIVE(false),
    NOTICE_SEND(true),
    CONFIG_READ(false),
    CONFIG_PUBLISH(true),
    AUDIT_READ(true),
    /** Operational emergency flag only (e.g. "carries EpiPen"), never diagnosis or history. */
    HEALTH_OPERATIONAL_FLAG_READ(true),
    HEALTH_RECORD_READ(true),
    SAFEGUARDING_CASE_READ(true);

    private final boolean alwaysAudited;

    Permission(boolean alwaysAudited) {
        this.alwaysAudited = alwaysAudited;
    }

    public boolean alwaysAudited() {
        return alwaysAudited;
    }
}
