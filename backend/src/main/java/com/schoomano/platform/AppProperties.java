package com.schoomano.platform;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("schoomano")
public record AppProperties(Auth auth, Cors cors, Sms sms) {
    /**
     * {@code otpPepper} is a server-side secret mixed into every stored code hash (ADR 0009). Without it a leaked
     * database would let someone recover 6-digit codes by brute force. Required outside dev mode.
     */
    public record Auth(boolean devMode, String otpPepper) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /** {@code provider}: "console" (local only: codes go to the log). Real SMS providers plug in behind SmsSender. */
    public record Sms(String provider) {
    }
}
