package com.schoomano.core.access;

/** Restricted partitions are never reachable through ordinary role permissions or guardian links. */
public enum Sensitivity {
    ORDINARY,
    HEALTH,
    SAFEGUARDING;

    public Permission requiredPermission() {
        return switch (this) {
            case ORDINARY -> null;
            case HEALTH -> Permission.HEALTH_RECORD_READ;
            case SAFEGUARDING -> Permission.SAFEGUARDING_CASE_READ;
        };
    }
}
