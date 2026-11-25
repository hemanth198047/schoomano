package com.schoomano.domain.access;

import static com.schoomano.domain.access.Permission.*;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Default role bundles. Tenants will be able to define custom roles (Stage B, M01) but those roles
 * can only combine these permissions. Note group_admin deliberately lacks health and safeguarding
 * reads: seniority does not imply access to restricted child records.
 */
public final class RoleCatalog {

    private final Map<String, Set<Permission>> roles;

    public RoleCatalog(Map<String, Set<Permission>> roles) {
        this.roles = Map.copyOf(roles);
    }

    public static RoleCatalog defaults() {
        return new RoleCatalog(Map.of(
            "group_admin", EnumSet.of(CAMPUS_READ, CAMPUS_MANAGE, ACADEMIC_STRUCTURE_MANAGE, IMPORT_MANAGE, PERSON_MANAGE, ROLE_GRANT_MANAGE,
                SUPPORT_GRANT_MANAGE,
                STUDENT_READ_BASIC, STUDENT_READ_ACADEMIC, STUDENT_WRITE, ATTENDANCE_READ, ATTENDANCE_CORRECT, FEE_READ, FEE_MANAGE,
                NOTICE_SEND, CONFIG_READ, CONFIG_PUBLISH, AUDIT_READ, TIMETABLE_READ, TIMETABLE_MANAGE,
                ASSESSMENT_READ, ASSESSMENT_WRITE, REPORT_PUBLISH, ADMISSION_READ, ADMISSION_MANAGE),
            "campus_admin", EnumSet.of(CAMPUS_READ, ACADEMIC_STRUCTURE_MANAGE, IMPORT_MANAGE, PERSON_MANAGE, STUDENT_READ_BASIC, STUDENT_READ_ACADEMIC,
                STUDENT_WRITE, ATTENDANCE_READ, ATTENDANCE_CORRECT, FEE_READ, NOTICE_SEND, CONFIG_READ,
                TIMETABLE_READ, TIMETABLE_MANAGE, ASSESSMENT_READ, ASSESSMENT_WRITE, REPORT_PUBLISH,
                ADMISSION_READ, ADMISSION_MANAGE),
            "principal", EnumSet.of(CAMPUS_READ, STUDENT_READ_BASIC, STUDENT_READ_ACADEMIC, ATTENDANCE_READ,
                ATTENDANCE_CORRECT, NOTICE_SEND, CONFIG_READ, AUDIT_READ, TIMETABLE_READ, ASSESSMENT_READ, REPORT_PUBLISH, ADMISSION_READ),
            "teacher", EnumSet.of(CAMPUS_READ, STUDENT_READ_BASIC, STUDENT_READ_ACADEMIC, ATTENDANCE_READ,
                ATTENDANCE_MARK, NOTICE_SEND, TIMETABLE_READ, ASSESSMENT_READ, ASSESSMENT_WRITE),
            "finance_officer", EnumSet.of(CAMPUS_READ, STUDENT_READ_BASIC, FEE_READ, FEE_MANAGE),
            "transport_attendant", EnumSet.of(STUDENT_READ_BASIC, HEALTH_OPERATIONAL_FLAG_READ),
            "nurse", EnumSet.of(STUDENT_READ_BASIC, HEALTH_OPERATIONAL_FLAG_READ, HEALTH_RECORD_READ),
            "safeguarding_lead", EnumSet.of(STUDENT_READ_BASIC, SAFEGUARDING_CASE_READ)));
    }

    public Set<Permission> permissionsOf(String roleKey) {
        return roles.getOrDefault(roleKey, Set.of());
    }
}
