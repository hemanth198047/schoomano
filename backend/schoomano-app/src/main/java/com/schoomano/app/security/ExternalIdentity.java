package com.schoomano.app.security;

/** Issuer + subject as asserted by the identity provider (or the dev token filter). */
public record ExternalIdentity(String issuer, String subject) {
}
