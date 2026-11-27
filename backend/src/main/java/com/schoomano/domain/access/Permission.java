package com.schoomano.domain.access;

/**
 * Code-defined permissions. Roles are tenant-configurable bundles of these; permissions are not,
 * because each one is enforced by server code and covered by tests.
 */
public enum Permission {
    CAMPUS_READ(false),
    CAMPUS_MANAGE(true),
    /** Grades, subjects, terms, sections, calendar, rollover (M04). */
    ACADEMIC_STRUCTURE_MANAGE(true),
    /** Validate, commit and roll back imports from the old school system (M29). */
    IMPORT_MANAGE(true),
    PERSON_MANAGE(true),
    ROLE_GRANT_MANAGE(true),
    /** Approve or end platform support access to this school (M30). */
    SUPPORT_GRANT_MANAGE(true),
    STUDENT_READ_BASIC(false),
    STUDENT_READ_ACADEMIC(false),
    STUDENT_WRITE(true),
    ATTENDANCE_READ(false),
    ATTENDANCE_MARK(false),
    ATTENDANCE_CORRECT(true),
    /** Marks and assessments of a class (M09). */
    ASSESSMENT_READ(false),
    ASSESSMENT_WRITE(true),
    /** Publish or re-publish report cards (M09). */
    REPORT_PUBLISH(true),
    /** Enquiries, applications and offers for a campus (M02). */
    ADMISSION_READ(false),
    ADMISSION_MANAGE(true),
    /** Syllabus per grade and subject (M07). */
    CURRICULUM_MANAGE(true),
    /** Coverage and homework of a class (M08). */
    LEARNING_READ(false),
    LEARNING_WRITE(true),
    /** Confidential support cases about a child (M10). Never granted to guardians or ordinary teachers. */
    STUDENT_SUPPORT_READ(false),
    STUDENT_SUPPORT_WRITE(true),
    /** Raise a safeguarding concern about a child. Write-only: it does not allow reading reports (M11). */
    SAFEGUARDING_REPORT(true),
    TIMETABLE_READ(false),
    TIMETABLE_MANAGE(true),
    FEE_READ(false),
    FEE_PAY(true),
    FEE_MANAGE(true),
    PROCUREMENT_READ(false),
    PROCUREMENT_REQUEST(true),
    PROCUREMENT_APPROVE(true),
    PROCUREMENT_APPROVE_LARGE(true),
    BUDGET_MANAGE(true),
    PAYMENT_RECORD(true),
    HR_READ(false),
    HR_MANAGE(true),
    LEAVE_REQUEST(true),
    LEAVE_APPROVE(true),
    TRANSPORT_READ(false),
    TRANSPORT_MANAGE(true),
    BOARDING_RECORD(true),
    ACTIVITY_READ(false),
    ACTIVITY_MANAGE(true),
    ACTIVITY_ATTENDANCE(true),
    TRIP_READ(false),
    TRIP_MANAGE(true),
    TRIP_HEADCOUNT(true),
    FACILITY_READ(false),
    FACILITY_REQUEST(true),
    FACILITY_MANAGE(true),
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
