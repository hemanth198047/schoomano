package com.schoomano.platform;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Encrypts secrets that must be recoverable (a webhook signing secret is needed in the clear to sign). AES-256-GCM with a
 * fresh random nonce each time; the stored text is "v1:" and base64 of nonce and ciphertext. With no key set (local
 * development) values are stored as they are, and anything stored that way is still read. A value that was encrypted can
 * only be read back with the key it was written with.
 */
@Component
public class SecretBox {

    private static final Logger log = LoggerFactory.getLogger(SecretBox.class);
    private static final String PREFIX = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    @Autowired
    public SecretBox(@Value("${schoomano.secrets.key:}") String base64Key, Environment env) {
        this(base64Key, Arrays.asList(env.getActiveProfiles()).contains("prod"));
    }

    public SecretBox(String base64Key) {
        this(base64Key, false);
    }

    /** With required set (the prod profile) a missing key stops the application from starting. */
    public SecretBox(String base64Key, boolean required) {
        if (base64Key == null || base64Key.isBlank()) {
            if (required) {
                throw new IllegalStateException("schoomano.secrets.key must be set with the prod profile (32 bytes, base64)");
            }
            this.key = null;
            log.warn("schoomano.secrets.key is not set: webhook secrets are stored without encryption");
        } else {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 32) {
                throw new IllegalStateException("schoomano.secrets.key must be 32 bytes, base64 encoded");
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
    }

    public boolean encrypting() {
        return key != null;
    }

    public String seal(String plain) {
        if (key == null) {
            return plain;
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt", e);
        }
    }

    public String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("an encrypted secret is stored but schoomano.secrets.key is not set");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("could not decrypt (wrong key?)", e);
        }
    }
}
