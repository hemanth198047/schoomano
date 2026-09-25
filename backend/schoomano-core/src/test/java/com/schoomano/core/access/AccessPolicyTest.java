package com.schoomano.core.access;

import static com.schoomano.core.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.schoomano.core.tenancy.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AccessPolicyTest {

    private final AccessPolicy policy = new AccessPolicy(RoleCatalog.defaults());

    private static Actor staff(UUID person, String role, com.schoomano.core.tenancy.ScopeNode scope) {
        return Actor.user(person, TENANT_A, List.of(new RoleGrant(role, scope, YEAR_START, null)), List.of());
    }

    private static final Set<GuardianCapability> FULL = EnumSet.allOf(GuardianCapability.class);

    private static Actor meera() {
        return Actor.user(MEERA_GUARDIAN, TENANT_A, List.of(), List.of(
            new GuardianLink(ANAYA, GuardianLink.Status.ACTIVE, FULL, YEAR_START, null),
            new GuardianLink(VIVAAN, GuardianLink.Status.ACTIVE, FULL, YEAR_START, null)));
    }

    private static Actor karanSeparated() {
        return Actor.user(KARAN_GUARDIAN, TENANT_A, List.of(), List.of(
            new GuardianLink(ANAYA, GuardianLink.Status.RESTRICTED,
                EnumSet.of(GuardianCapability.RECEIVE_NOTICES), YEAR_START, null)));
    }

    private static Resource studentRecord(UUID student, com.schoomano.core.tenancy.ScopePath scope) {
        return Resource.ordinary("student", student, scope, student);
    }

    @Nested
    class TenantIsolation {
        @Test
        void groupAdminCannotReachAnotherTenant() {
            var admin = staff(ASHA_ADMIN, "group_admin", GROUP_A);
            var foreign = studentRecord(OTHER_TENANT_STUDENT, otherTenantPath());
            var d = policy.decide(admin, Permission.STUDENT_READ_BASIC, foreign, NOW);
            assertFalse(d.allowed());
            assertTrue(d.mustAudit(), "cross-tenant attempts are always audited");
        }

        @Test
        void guardianLinkToForeignIdDoesNotCrossTenants() {
            // Even a forged link pointing at a foreign student id cannot pass the tenant check.
            var forged = Actor.user(MEERA_GUARDIAN, TENANT_A, List.of(), List.of(
                new GuardianLink(OTHER_TENANT_STUDENT, GuardianLink.Status.ACTIVE, FULL, YEAR_START, null)));
            var d = policy.decide(forged, Permission.ATTENDANCE_READ,
                studentRecord(OTHER_TENANT_STUDENT, otherTenantPath()), NOW);
            assertFalse(d.allowed());
        }
    }

    @Nested
    class RoleScope {
        @Test
        void teacherActsOnlyWithinTheirCampus() {
            var ravi = staff(RAVI_TEACHER, "teacher", NORTH);
            assertTrue(policy.decide(ravi, Permission.ATTENDANCE_MARK, studentRecord(ANAYA, northPath()), NOW).allowed());
            assertFalse(policy.decide(ravi, Permission.ATTENDANCE_MARK, studentRecord(VIVAAN, southPath()), NOW).allowed());
        }

        @Test
        void teacherCannotPublishConfig() {
            var ravi = staff(RAVI_TEACHER, "teacher", NORTH);
            var cfg = Resource.ordinary("config", UUID.randomUUID(), northPath(), null);
            assertFalse(policy.decide(ravi, Permission.CONFIG_PUBLISH, cfg, NOW).allowed());
        }

        @Test
        void expiredGrantNoLongerApplies() {
            var ended = Actor.user(RAVI_TEACHER, TENANT_A,
                List.of(new RoleGrant("teacher", NORTH, YEAR_START, NOW.minusSeconds(1))), List.of());
            assertFalse(policy.decide(ended, Permission.ATTENDANCE_MARK, studentRecord(ANAYA, northPath()), NOW).allowed());
        }

        @Test
        void futureGrantDoesNotApplyYet() {
            var future = Actor.user(RAVI_TEACHER, TENANT_A,
                List.of(new RoleGrant("teacher", NORTH, NOW.plusSeconds(3600), null)), List.of());
            assertFalse(policy.decide(future, Permission.ATTENDANCE_MARK, studentRecord(ANAYA, northPath()), NOW).allowed());
        }

        @Test
        void personCanBeTeacherAndParentAtOnce() {
            var both = Actor.user(RAVI_TEACHER, TENANT_A,
                List.of(new RoleGrant("teacher", NORTH, YEAR_START, null)),
                List.of(new GuardianLink(VIVAAN, GuardianLink.Status.ACTIVE, FULL, YEAR_START, null)));
            // As teacher on North, marks Anaya; as parent, pays for own child on South; not a teacher on South.
            assertTrue(policy.decide(both, Permission.ATTENDANCE_MARK, studentRecord(ANAYA, northPath()), NOW).allowed());
            var pay = policy.decide(both, Permission.FEE_PAY, studentRecord(VIVAAN, southPath()), NOW);
            assertTrue(pay.allowed());
            assertEquals(AccessDecision.Basis.RELATIONSHIP, pay.basis());
            assertFalse(policy.decide(both, Permission.ATTENDANCE_MARK, studentRecord(VIVAAN, southPath()), NOW).allowed());
        }
    }

    @Nested
    class GuardianRelationships {
        @Test
        void guardianWithTwoChildrenOnTwoCampusesSeesBothAndSwitchesContext() {
            var m = meera();
            assertTrue(policy.decide(m, Permission.ATTENDANCE_READ, studentRecord(ANAYA, northPath()), NOW).allowed());
            assertTrue(policy.decide(m, Permission.ATTENDANCE_READ, studentRecord(VIVAAN, southPath()), NOW).allowed());
            assertEquals(List.of(ANAYA, VIVAAN), policy.studentsFor(m, GuardianCapability.VIEW_ATTENDANCE, NOW));
        }

        @Test
        void guardianCannotSeeUnrelatedChildOnSameCampus() {
            var unrelated = UUID.randomUUID();
            assertFalse(policy.decide(meera(), Permission.ATTENDANCE_READ, studentRecord(unrelated, northPath()), NOW).allowed());
        }

        @Test
        void guardianCannotUseStaffOnlyPermissions() {
            assertFalse(policy.decide(meera(), Permission.ATTENDANCE_MARK, studentRecord(ANAYA, northPath()), NOW).allowed());
            assertFalse(policy.decide(meera(), Permission.STUDENT_WRITE, studentRecord(ANAYA, northPath()), NOW).allowed());
        }

        @Test
        void separatedGuardianGetsNoticesOnlyNotRestrictedData() {
            var k = karanSeparated();
            var anaya = studentRecord(ANAYA, northPath());
            assertTrue(policy.decide(k, Permission.NOTICE_RECEIVE, anaya, NOW).allowed());
            assertFalse(policy.decide(k, Permission.STUDENT_READ_ACADEMIC, anaya, NOW).allowed());
            assertFalse(policy.decide(k, Permission.ATTENDANCE_READ, anaya, NOW).allowed());
            assertFalse(policy.decide(k, Permission.FEE_PAY, anaya, NOW).allowed());
            assertFalse(policy.decide(k, Permission.CONSENT_GIVE, anaya, NOW).allowed());
            assertTrue(policy.studentsFor(k, GuardianCapability.PAY_FEES, NOW).isEmpty());
        }

        @Test
        void suspendedLinkGrantsNothing() {
            var suspended = Actor.user(KARAN_GUARDIAN, TENANT_A, List.of(), List.of(
                new GuardianLink(ANAYA, GuardianLink.Status.SUSPENDED, FULL, YEAR_START, null)));
            assertFalse(policy.decide(suspended, Permission.STUDENT_READ_BASIC, studentRecord(ANAYA, northPath()), NOW).allowed());
        }

        @Test
        void guardianNeverReachesRestrictedPartitions() {
            var healthRecord = new Resource("health_record", UUID.randomUUID(), northPath(), Sensitivity.HEALTH, ANAYA, Set.of());
            assertFalse(policy.decide(meera(), Permission.HEALTH_RECORD_READ, healthRecord, NOW).allowed());
        }
    }

    @Nested
    class RestrictedCases {
        private final UUID caseId = UUID.randomUUID();
        private final Resource safeguardingCase =
            new Resource("safeguarding_case", caseId, northPath(), Sensitivity.SAFEGUARDING, ANAYA, Set.of());

        @Test
        void groupAdminCannotReadSafeguardingCaseByDefault() {
            var d = policy.decide(staff(ASHA_ADMIN, "group_admin", GROUP_A), Permission.SAFEGUARDING_CASE_READ, safeguardingCase, NOW);
            assertFalse(d.allowed());
            assertTrue(d.mustAudit());
        }

        @Test
        void ordinaryStudentReadNeverOpensARestrictedRecord() {
            var d = policy.decide(staff(RAVI_TEACHER, "teacher", NORTH), Permission.STUDENT_READ_BASIC, safeguardingCase, NOW);
            assertFalse(d.allowed(), "student search permissions must not surface case details");
        }

        @Test
        void designatedLeadInScopeCanRead() {
            var d = policy.decide(staff(FARAH_LEAD, "safeguarding_lead", NORTH), Permission.SAFEGUARDING_CASE_READ, safeguardingCase, NOW);
            assertTrue(d.allowed());
            assertTrue(d.mustAudit());
        }

        @Test
        void designatedLeadOnAnotherCampusCannot() {
            var d = policy.decide(staff(FARAH_LEAD, "safeguarding_lead", SOUTH), Permission.SAFEGUARDING_CASE_READ, safeguardingCase, NOW);
            assertFalse(d.allowed());
        }

        @Test
        void assignedCaseTeamMemberCanRead() {
            var withTeam = new Resource("safeguarding_case", caseId, northPath(), Sensitivity.SAFEGUARDING, ANAYA, Set.of(RAVI_TEACHER));
            var d = policy.decide(staff(RAVI_TEACHER, "teacher", NORTH), Permission.SAFEGUARDING_CASE_READ, withTeam, NOW);
            assertEquals(AccessDecision.Basis.CASE_TEAM, d.basis());
        }

        @Test
        void breakGlassWorksOnlyForThatRecordAndOnlyUntilExpiry() {
            var bg = new BreakGlassGrant("safeguarding_case", caseId,
                "Child missing at dispersal; need case safety plan now", NOW, NOW.plus(Duration.ofHours(1)));
            var principal = staff(ASHA_ADMIN, "principal", NORTH).withBreakGlass(bg);

            var d = policy.decide(principal, Permission.SAFEGUARDING_CASE_READ, safeguardingCase, NOW.plusSeconds(60));
            assertTrue(d.allowed());
            assertEquals(AccessDecision.Basis.BREAK_GLASS, d.basis());
            assertTrue(d.mustAudit());

            var otherCase = new Resource("safeguarding_case", UUID.randomUUID(), northPath(), Sensitivity.SAFEGUARDING, ANAYA, Set.of());
            assertFalse(policy.decide(principal, Permission.SAFEGUARDING_CASE_READ, otherCase, NOW.plusSeconds(60)).allowed());
            assertFalse(policy.decide(principal, Permission.SAFEGUARDING_CASE_READ, safeguardingCase, NOW.plus(Duration.ofHours(2))).allowed());
        }

        @Test
        void breakGlassRejectsWeakJustificationOrLongWindow() {
            assertThrows(IllegalArgumentException.class, () ->
                new BreakGlassGrant("safeguarding_case", caseId, "urgent", NOW, NOW.plusSeconds(600)));
            assertThrows(IllegalArgumentException.class, () ->
                new BreakGlassGrant("safeguarding_case", caseId, "Emergency contact needed for trip incident", NOW, NOW.plus(Duration.ofHours(5))));
        }

        @Test
        void transportSeesOperationalFlagButNotHealthHistory() {
            var attendant = staff(UUID.randomUUID(), "transport_attendant", NORTH);
            var flag = Resource.ordinary("health_operational_flag", UUID.randomUUID(), northPath(), ANAYA);
            var history = new Resource("health_record", UUID.randomUUID(), northPath(), Sensitivity.HEALTH, ANAYA, Set.of());
            assertTrue(policy.decide(attendant, Permission.HEALTH_OPERATIONAL_FLAG_READ, flag, NOW).allowed());
            assertFalse(policy.decide(attendant, Permission.HEALTH_RECORD_READ, history, NOW).allowed());
        }
    }

    @Nested
    class SupportAccess {
        private Actor support(Instant start, Instant end, Instant revoked) {
            return new Actor(null, TENANT_A, Actor.Kind.SUPPORT, List.of(), List.of(), List.of(),
                Optional.of(new SupportSession(TENANT_A, start, end, revoked)));
        }

        @Test
        void supportNeedsAnActiveTimeLimitedGrant() {
            var cfg = Resource.ordinary("config", UUID.randomUUID(), northPath(), null);
            assertTrue(policy.decide(support(NOW.minusSeconds(60), NOW.plusSeconds(3600), null), Permission.CONFIG_READ, cfg, NOW).allowed());
            assertFalse(policy.decide(support(NOW.minusSeconds(7200), NOW.minusSeconds(60), null), Permission.CONFIG_READ, cfg, NOW).allowed());
            assertFalse(policy.decide(support(NOW.minusSeconds(60), NOW.plusSeconds(3600), NOW.minusSeconds(1)), Permission.CONFIG_READ, cfg, NOW).allowed());
        }

        @Test
        void supportCannotReadAcademicOrRestrictedData() {
            var s = support(NOW.minusSeconds(60), NOW.plusSeconds(3600), null);
            assertFalse(policy.decide(s, Permission.STUDENT_READ_ACADEMIC, studentRecord(ANAYA, northPath()), NOW).allowed());
            var health = new Resource("health_record", UUID.randomUUID(), northPath(), Sensitivity.HEALTH, ANAYA, Set.of());
            assertFalse(policy.decide(s, Permission.HEALTH_RECORD_READ, health, NOW).allowed());
        }

        @Test
        void supportActorWithoutSessionCannotBeConstructed() {
            assertThrows(IllegalArgumentException.class, () ->
                new Actor(null, TenantId.of("10000000-0000-4000-8000-000000000001"), Actor.Kind.SUPPORT,
                    List.of(), List.of(), List.of(), Optional.empty()));
        }
    }
}
