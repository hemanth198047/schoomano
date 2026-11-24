package com.schoomano.platform;

/**
 * Sends the sign-in code to a parent's phone. The real adapter is an Indian provider registered under TRAI DLT
 * (sender id and template approval take days to weeks, ADR 0009); until then only the local console adapter
 * exists. Implementations must never log the code outside local development.
 */
public interface SmsSender {

    /** @param phoneE164 e.g. +919845012345 */
    void sendOtp(String phoneE164, String code);
}
