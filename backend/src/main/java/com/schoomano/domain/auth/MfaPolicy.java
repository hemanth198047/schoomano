package com.schoomano.domain.auth;

import java.util.Collection;
import java.util.Set;

/**
 * Staff sign in through the school's OIDC provider; roles that can change money, people or structure must have
 * used a second factor there. The provider reports how the person authenticated in the standard "amr" claim
 * (RFC 8176), and this policy only decides whether that is enough. Parents use the phone OTP and are not
 * covered: their sessions cannot hold any of these roles.
 */
public final class MfaPolicy {

    /** Roles that need a second factor (backlog B4: admin and finance roles). */
    private static final Set<String> ROLES = Set.of("group_admin", "campus_admin", "finance_officer");
    /** "mfa" = several factors; "otp" = one-time code (TOTP); "hwk" = hardware key. */
    private static final Set<String> SECOND_FACTOR = Set.of("mfa", "otp", "hwk");

    private MfaPolicy() {
    }

    public static boolean requiresMfa(Collection<String> activeRoleKeys) {
        return activeRoleKeys != null && activeRoleKeys.stream().anyMatch(ROLES::contains);
    }

    public static boolean satisfied(Collection<String> authenticationMethods) {
        return authenticationMethods != null && authenticationMethods.stream().anyMatch(SECOND_FACTOR::contains);
    }
}
