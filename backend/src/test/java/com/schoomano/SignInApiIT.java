package com.schoomano;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.schoomano.platform.SmsSender;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * B4 acceptance (parent phone OTP, ADR 0009): a code works once, five wrong guesses lock it, unknown numbers are
 * indistinguishable from known ones, sessions rotate and can be ended by the office, and a phone session never
 * carries staff roles. Each test uses its own guardian and "client address" so the per-number and per-address
 * limits (which are real here) do not couple the tests.
 */
@Import(SignInApiIT.CaptureSms.class)
class SignInApiIT extends ApiITSupport {

    /** A student of our own, so these tests never change who the seeded children' guardians are. */
    static final String SANVI = "13000000-0000-4000-8000-000000000990";
    static final Map<String, String> CODES = new ConcurrentHashMap<>();

    /** Stands in for the SMS provider: keeps the last code "sent" to each number. */
    @TestConfiguration
    static class CaptureSms {
        @Bean
        @Primary
        SmsSender captureSms() {
            return (phone, code) -> CODES.put(phone, code);
        }
    }

    /** A synthetic guardian of Sanvi with their own number; returns {personId, e164}. */
    private static String[] guardian(int n) throws Exception {
        ownerUpdate("""
            insert into person (id, tenant_id, school_ref, given_name, family_name)
            values ('%s', '10000000-0000-4000-8000-000000000001', 'STU-IT-SIGNIN', 'Sanvi', 'Synthetic')
            on conflict (id) do nothing
            """.formatted(SANVI));
        String id = String.format("13000000-0000-4000-8000-0000000009%02d", n);
        String phone = String.format("+9198100%05d", n);
        ownerUpdate("""
            insert into person (id, tenant_id, school_ref, given_name, family_name, mobile_e164)
            values ('%1$s', '10000000-0000-4000-8000-000000000001', 'GRD-IT-%2$02d', 'Guardian%2$02d', 'Synthetic', '%3$s');
            insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type, status)
            values ('10000000-0000-4000-8000-000000000001', '%1$s', '%4$s', 'GUARDIAN', 'ACTIVE');
            """.formatted(id, n, phone, SANVI));
        return new String[] {id, phone};
    }

    private ResultActions requestCode(String phone, int clientNo) throws Exception {
        return mvc.perform(post("/api/v1/auth/otp/request").contentType(MediaType.APPLICATION_JSON)
            .content("{\"phone\":\"" + phone + "\"}").with(r -> {
                r.setRemoteAddr("10.20.0." + clientNo);
                return r;
            }));
    }

    private ResultActions verify(String phone, String code, String device) throws Exception {
        return mvc.perform(post("/api/v1/auth/otp/verify").contentType(MediaType.APPLICATION_JSON)
            .content("{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\",\"deviceLabel\":\"" + device + "\"}"));
    }

    /** The code the (captured) SMS carried. The SMS is sent in the background, so wait briefly for it. */
    private static String sentCode(String phone) throws Exception {
        for (int i = 0; i < 100 && !CODES.containsKey(phone); i++) {
            Thread.sleep(30);
        }
        String code = CODES.get(phone);
        assertNotNull(code, "no code was sent to " + phone);
        return code;
    }

    private static String wrongCodeFor(String code) {
        return code.equals("111111") ? "222222" : "111111";
    }

    /** Full sign-in; returns {accessToken, refreshToken}. */
    private String[] signIn(String phone, int clientNo, String device) throws Exception {
        requestCode(phone, clientNo).andExpect(status().isAccepted());
        String body = verify(phone, sentCode(phone), device).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return new String[] {JsonPath.read(body, "$.accessToken"), JsonPath.read(body, "$.refreshToken")};
    }

    private ResultActions me(String accessToken) throws Exception {
        return mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken));
    }

    @Test
    void aKnownAndAnUnknownNumberAreTreatedTheSame() throws Exception {
        var g = guardian(1);
        requestCode(g[1], 1).andExpect(status().isAccepted()).andExpect(jsonPath("$.resendAfterSeconds").value(30));
        requestCode("+919000000001", 1).andExpect(status().isAccepted()).andExpect(jsonPath("$.resendAfterSeconds").value(30));

        // A wrong code and a code for a number that never received one fail with the very same answer.
        String known = verify(g[1], wrongCodeFor(sentCode(g[1])), "x").andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_code")).andReturn().getResponse().getContentAsString();
        String unknown = verify("+919000000001", "123456", "x").andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("invalid_code")).andReturn().getResponse().getContentAsString();
        assertEquals(JsonPath.<String>read(known, "$.detail"), JsonPath.<String>read(unknown, "$.detail"));
        assertEquals(0, CODES.keySet().stream().filter(p -> p.equals("+919000000001")).count(), "unknown numbers get no SMS");
    }

    @Test
    void aCorrectCodeStartsASessionThatTheNormalPipelineUnderstands() throws Exception {
        var g = guardian(2);
        var tokens = signIn(g[1], 2, "Pixel 6a");
        me(tokens[0]).andExpect(status().isOk())
            .andExpect(jsonPath("$.tenant.slug").value("demo-group"))
            .andExpect(jsonPath("$.children", hasSize(1)))
            .andExpect(jsonPath("$.children[0].displayName").value("Sanvi"))
            .andExpect(jsonPath("$.roles", hasSize(0)));
    }

    @Test
    void aCodeWorksOnlyOnce() throws Exception {
        var g = guardian(3);
        requestCode(g[1], 3).andExpect(status().isAccepted());
        String code = sentCode(g[1]);
        verify(g[1], code, "a").andExpect(status().isOk());
        verify(g[1], code, "b").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_code"));
    }

    @Test
    void fiveWrongGuessesLockTheCodeEvenForTheRightOne() throws Exception {
        var g = guardian(4);
        requestCode(g[1], 4).andExpect(status().isAccepted());
        String code = sentCode(g[1]);
        for (int i = 0; i < 5; i++) {
            verify(g[1], wrongCodeFor(code), "x").andExpect(status().isUnauthorized());
        }
        verify(g[1], code, "x").andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("otp_locked"));
    }

    @Test
    void anotherCodeCannotBeAskedForStraightAway() throws Exception {
        var g = guardian(5);
        requestCode(g[1], 5).andExpect(status().isAccepted());
        requestCode(g[1], 5).andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("otp_resend_too_soon"))
            .andExpect(jsonPath("$.retryAfterSeconds").exists());
    }

    @Test
    void badInputIsRejectedBeforeAnyCodeIsInvolved() throws Exception {
        requestCode("12345", 6).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_mobile"));
        verify("98100 00007", "12ab", "x").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_code_format"));
    }

    @Test
    void refreshingRotatesTheTokensAndAReplayedRefreshTokenEndsTheSession() throws Exception {
        var g = guardian(7);
        var first = signIn(g[1], 7, "Redmi");
        String body = mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + first[1] + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String newAccess = JsonPath.read(body, "$.accessToken");

        me(first[0]).andExpect(status().isUnauthorized());
        me(newAccess).andExpect(status().isOk());

        mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + first[1] + "\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_refresh_token"));
        me(newAccess).andExpect(status().isUnauthorized());
    }

    @Test
    void loggingOutEndsTheSession() throws Exception {
        var g = guardian(8);
        var tokens = signIn(g[1], 8, "Phone");
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + tokens[0])).andExpect(status().isNoContent());
        me(tokens[0]).andExpect(status().isUnauthorized());
    }

    @Test
    void garbageTokensAreJustUnauthenticated() throws Exception {
        me("smo_" + "A".repeat(43)).andExpect(status().isUnauthorized());
        me("smo_short").andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"nope\"}"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_refresh_token"));
    }

    @Test
    void aTeacherWhoIsAlsoAParentGetsNoStaffRolesFromJustAPhoneCode() throws Exception {
        // Asha is the group admin. Give her a number and make her a guardian too.
        guardian(98);
        ownerUpdate("""
            update person set mobile_e164 = '+919810009999' where id = '13000000-0000-4000-8000-000000000001';
            insert into guardian_relationship (tenant_id, guardian_person_id, student_person_id, relationship_type, status)
            values ('10000000-0000-4000-8000-000000000001', '13000000-0000-4000-8000-000000000001', '%s', 'GUARDIAN', 'ACTIVE');
            """.formatted(SANVI));
        mvc.perform(get("/api/v1/me").header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0].roleKey").value("group_admin"));

        var tokens = signIn("+919810009999", 9, "Asha's phone");
        me(tokens[0]).andExpect(status().isOk())
            .andExpect(jsonPath("$.personId").value("13000000-0000-4000-8000-000000000001"))
            .andExpect(jsonPath("$.roles", hasSize(0)));
        me(tokens[0]).andExpect(jsonPath("$.children[0].displayName").value("Sanvi"));
    }

    @Test
    void theOfficeCanSeeAndEndAGuardiansSessions() throws Exception {
        var g = guardian(10);
        var tokens = signIn(g[1], 10, "Samsung A14");
        String base = "/api/v1/guardians/" + g[0] + "/sessions";

        mvc.perform(get(base).header("Authorization", dev("dev-asha")))
            .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].deviceLabel").value("Samsung A14"));
        mvc.perform(get(base).header("Authorization", dev("dev-ravi"))).andExpect(status().isNotFound());
        mvc.perform(post(base + "/revoke").header("Authorization", dev("dev-ravi")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Lost phone reported by parent\"}")).andExpect(status().isNotFound());

        mvc.perform(post(base + "/revoke").header("Authorization", dev("dev-asha")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"short\"}")).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("reason_required"));
        mvc.perform(post(base + "/revoke").header("Authorization", dev("dev-asha")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"reason\":\"Lost phone reported by parent\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.revoked", is(1)));

        me(tokens[0]).andExpect(status().isUnauthorized());
        assertEquals(1, ownerCount("select count(*) from audit_event where action = 'guardian.sessions_revoked' and resource_id = '"
            + g[0] + "'"));
    }

    @Test
    void whenTheOfficeChangesTheNumberTheOldSessionEnds() throws Exception {
        var g = guardian(11);
        var tokens = signIn(g[1], 11, "Old phone");
        me(tokens[0]).andExpect(status().isOk());

        mvc.perform(patch("/api/v1/guardians/" + g[0]).header("Authorization", dev("dev-asha")).contentType(MediaType.APPLICATION_JSON)
            .content("{\"mobile\":\"98100 77011\",\"reason\":\"Parent changed number after SIM loss\"}")).andExpect(status().isOk());

        me(tokens[0]).andExpect(status().isUnauthorized());
        // The old number can no longer get a code that works; the new one can sign in.
        requestCode(g[1], 12).andExpect(status().isAccepted());
        verify(g[1], "123456", "x").andExpect(status().isUnauthorized());
        var fresh = signIn("+919810077011", 12, "New phone");
        me(fresh[0]).andExpect(status().isOk());
    }
}
