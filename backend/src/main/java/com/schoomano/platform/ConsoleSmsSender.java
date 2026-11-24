package com.schoomano.platform;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * LOCAL DEVELOPMENT ONLY: "sends" the code by writing it to the backend log. Active only with
 * schoomano.sms.provider=console or dev mode, and refuses to start with the prod profile.
 */
@Component
@ConditionalOnExpression("'${schoomano.sms.provider:}' == 'console' or '${schoomano.auth.dev-mode:false}' == 'true'")
public class ConsoleSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSmsSender.class);

    public ConsoleSmsSender(Environment env) {
        if (Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("The console SMS adapter must never be enabled with the prod profile");
        }
    }

    @Override
    public void sendOtp(String phoneE164, String code) {
        String masked = "+91 ******" + phoneE164.substring(phoneE164.length() - 4);
        log.warn("[console SMS, local only] sign-in code for {} is {}", masked, code);
    }
}
