package com.schoomano.core.access;

/** Outcome of an authorization check. {@code mustAudit} decisions are written to audit_event. */
public record AccessDecision(boolean allowed, Basis basis, String reason, boolean mustAudit) {

    public enum Basis { ROLE, RELATIONSHIP, CASE_TEAM, BREAK_GLASS, SUPPORT, NONE }

    static AccessDecision allow(Basis basis, String reason, boolean audit) {
        return new AccessDecision(true, basis, reason, audit);
    }

    static AccessDecision deny(String reason, boolean audit) {
        return new AccessDecision(false, Basis.NONE, reason, audit);
    }
}
