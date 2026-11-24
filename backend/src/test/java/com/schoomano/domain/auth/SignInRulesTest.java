package com.schoomano.domain.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.schoomano.domain.access.Actor;
import com.schoomano.domain.access.RoleGrant;
import com.schoomano.domain.tenancy.ScopeLevel;
import com.schoomano.domain.tenancy.ScopeNode;
import com.schoomano.domain.tenancy.TenantId;
import java.util.List;
import java.util.UUID;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SignInRulesTest {

    @Test
    void codesAreSixDigitsAndKeepLeadingZeros() {
        var zero = new Random() {
            @Override
            public int nextInt(int bound) {
                return 42;
            }
        };
        assertEquals("000042", OtpPolicy.newCode(zero));
        var rnd = new Random(7);
        for (int i = 0; i < 1000; i++) {
            assertTrue(OtpPolicy.newCode(rnd).matches("^[0-9]{6}$"));
        }
    }

    @Test
    void typedCodesTolerateSpacesButNothingElse() {
        assertEquals("123456", OtpPolicy.normaliseTyped("123456"));
        assertEquals("123456", OtpPolicy.normaliseTyped(" 123 456 "));
        assertEquals("123456", OtpPolicy.normaliseTyped("123-456"));
        assertNull(OtpPolicy.normaliseTyped("12345"));
        assertNull(OtpPolicy.normaliseTyped("1234567"));
        assertNull(OtpPolicy.normaliseTyped("12345a"));
        assertNull(OtpPolicy.normaliseTyped(""));
        assertNull(OtpPolicy.normaliseTyped(null));
    }

    @Test
    void hashComparisonNeedsBothSidesAndAnExactMatch() {
        assertTrue(OtpPolicy.sameHash(new byte[] {1, 2, 3}, new byte[] {1, 2, 3}));
        assertFalse(OtpPolicy.sameHash(new byte[] {1, 2, 3}, new byte[] {1, 2, 4}));
        assertFalse(OtpPolicy.sameHash(new byte[] {1, 2, 3}, new byte[] {1, 2}));
        assertFalse(OtpPolicy.sameHash(null, new byte[] {1}));
        assertFalse(OtpPolicy.sameHash(new byte[] {1}, null));
    }

    @Test
    void limitsMatchAdr0009() {
        assertEquals(300, OtpPolicy.TTL_SECONDS);
        assertEquals(30, OtpPolicy.RESEND_SECONDS);
        assertEquals(5, OtpPolicy.MAX_ATTEMPTS);
        assertTrue(OtpPolicy.PER_NUMBER_PER_DAY > OtpPolicy.PER_NUMBER_PER_HOUR);
        assertTrue(SessionPolicy.ACCESS_TTL_SECONDS < SessionPolicy.REFRESH_TTL_SECONDS);
    }

    @Test
    void deviceLabelsAreCleanedAndBounded() {
        assertNull(SessionPolicy.cleanDeviceLabel(null));
        assertNull(SessionPolicy.cleanDeviceLabel("  \n\t "));
        assertEquals("Pixel 6a Chrome", SessionPolicy.cleanDeviceLabel("  Pixel\n6a   Chrome "));
        assertEquals(80, SessionPolicy.cleanDeviceLabel("x".repeat(200)).length());
    }

    @Test
    void adminAndFinanceRolesNeedASecondFactor() {
        assertTrue(MfaPolicy.requiresMfa(List.of("teacher", "finance_officer")));
        assertTrue(MfaPolicy.requiresMfa(List.of("group_admin")));
        assertTrue(MfaPolicy.requiresMfa(List.of("campus_admin")));
        assertFalse(MfaPolicy.requiresMfa(List.of("teacher")));
        assertFalse(MfaPolicy.requiresMfa(List.of()));
        assertFalse(MfaPolicy.requiresMfa(null));
    }

    @Test
    void passwordOnlyIsNotASecondFactor() {
        assertTrue(MfaPolicy.satisfied(List.of("pwd", "otp")));
        assertTrue(MfaPolicy.satisfied(List.of("mfa")));
        assertTrue(MfaPolicy.satisfied(List.of("hwk")));
        assertFalse(MfaPolicy.satisfied(List.of("pwd")));
        assertFalse(MfaPolicy.satisfied(List.of("sms")));
        assertFalse(MfaPolicy.satisfied(List.of()));
        assertFalse(MfaPolicy.satisfied(null));
    }

    @Test
    void aPhoneSessionKeepsGuardianLinksButNoStaffRoles() {
        var tenant = TenantId.of("10000000-0000-4000-8000-000000000001");
        var grant = new RoleGrant("campus_admin", new ScopeNode(ScopeLevel.GROUP, UUID.randomUUID()),
            java.time.Instant.parse("2026-01-01T00:00:00Z"), null);
        var teacherWhoIsAlsoAParent = Actor.user(UUID.randomUUID(), tenant, List.of(grant), List.of());
        assertEquals(1, teacherWhoIsAlsoAParent.grants().size());
        var viaPhone = teacherWhoIsAlsoAParent.guardianOnly();
        assertTrue(viaPhone.grants().isEmpty());
        assertTrue(viaPhone.breakGlass().isEmpty());
        assertEquals(teacherWhoIsAlsoAParent.personId(), viaPhone.personId());
    }
}
