package com.schoomano.platform;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs a webhook body. The receiver recomputes HMAC-SHA256 over "timestamp.body" with the endpoint's secret and compares
 * it with a v1 value of the X-Schoomano-Signature header, rejecting timestamps that are too old.
 */
public final class WebhookSigner {

    private WebhookSigner() { }

    public static String header(String secret, long epochSeconds, String body) {
        return header(List.of(secret), epochSeconds, body);
    }

    /** One v1 value per secret, newest first: while a secret is being replaced, the receiver may check against either. */
    public static String header(List<String> secrets, long epochSeconds, String body) {
        try {
            StringBuilder out = new StringBuilder("t=").append(epochSeconds);
            for (String secret : secrets) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                byte[] sig = mac.doFinal((epochSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
                out.append(",v1=").append(HexFormat.of().formatHex(sig));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
