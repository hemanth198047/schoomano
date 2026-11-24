package com.schoomano.platform;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * LOCAL AND TEST ONLY: pretends to be a payment provider. Webhooks are HMAC-SHA256 signed with a shared secret.
 * Active with schoomano.payments.provider=sandbox or dev mode, and refuses to start with the prod profile.
 */
@Component
@ConditionalOnExpression("'${schoomano.payments.provider:}' == 'sandbox' or '${schoomano.auth.dev-mode:false}' == 'true'")
public class SandboxPaymentGateway implements PaymentGateway {

    private final byte[] secret;

    public SandboxPaymentGateway(Environment env, @Value("${schoomano.payments.webhook-secret:sandbox-secret-local-only}") String secret) {
        if (Arrays.asList(env.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("The sandbox payment gateway must never be enabled with the prod profile");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String name() {
        return "sandbox";
    }

    @Override
    public boolean sandbox() {
        return true;
    }

    @Override
    public String createOrder(String receipt, long amountPaise) {
        return "order_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public boolean verify(String body, String signature) {
        if (signature == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), signature.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
