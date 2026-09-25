package com.schoomano.core.access;

import java.util.Optional;

public enum GuardianCapability {
    VIEW_ATTENDANCE,
    VIEW_ACADEMIC,
    PAY_FEES,
    GIVE_CONSENT,
    RECEIVE_NOTICES,
    PICKUP;

    /** Which relationship capability, if any, lets a guardian exercise a permission for their child. */
    public static Optional<GuardianCapability> forPermission(Permission permission) {
        return Optional.ofNullable(switch (permission) {
            case ATTENDANCE_READ -> VIEW_ATTENDANCE;
            case STUDENT_READ_ACADEMIC -> VIEW_ACADEMIC;
            case FEE_READ, FEE_PAY -> PAY_FEES;
            case CONSENT_GIVE -> GIVE_CONSENT;
            case NOTICE_RECEIVE -> RECEIVE_NOTICES;
            default -> null;
        });
    }
}
