package com.schoomano.platform;

/**
 * Sends the sign-in code to a parent's phone. The real adapter is an Indian provider registered under TRAI DLT
 * (sender id and template approval take days to weeks, ADR 0009); until then only the local console adapter
 * exists. Implementations must never log the code outside local development.
 */
public interface SmsSender {

    /** @param phoneE164 e.g. +919845012345 */
    void sendOtp(String phoneE164, String code);

    /**
     * Sends a short notice text. Texts are written to be safe on a lock screen: they never name a child or carry
     * details, only point to the app. A real adapter must use a TRAI DLT-approved template.
     */
    default void sendText(String phoneE164, String text) {
        throw new UnsupportedOperationException("This SMS adapter cannot send notices");
    }
}
