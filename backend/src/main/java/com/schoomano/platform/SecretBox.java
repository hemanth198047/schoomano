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
    private static final String PREFIX = "v2:";
    private static final String FIRST = "v1:";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;
    private final String keyId;
    private final java.util.Map<String, SecretKeySpec> all = new java.util.LinkedHashMap<>();

    @Autowired
    public SecretBox(@Value("${schoomano.secrets.key:}") String base64Key,
                     @Value("${schoomano.secrets.previous-keys:}") String previousKeys, Environment env) {
        this(base64Key, previousKeys, Arrays.asList(env.getActiveProfiles()).contains("prod"));
    }

    public SecretBox(String base64Key) {
        this(base64Key, "", false);
    }

    public SecretBox(String base64Key, boolean required) {
        this(base64Key, "", required);
    }

    /**
     * previousKeys (comma separated) are old keys that are still accepted for reading, so the key can be changed: set the
     * new key, list the old one here, restart with schoomano.secrets.rekey=true to re-seal everything, then remove it.
     * With required set (the prod profile) a missing key stops the application from starting.
     */
    public SecretBox(String base64Key, String previousKeys, boolean required) {
        if (base64Key == null || base64Key.isBlank()) {
            if (required) {
                throw new IllegalStateException("schoomano.secrets.key must be set with the prod profile (32 bytes, base64)");
            }
            this.key = null;
            this.keyId = null;
            log.warn("schoomano.secrets.key is not set: webhook secrets are stored without encryption");
        } else {
            this.key = parse(base64Key);
            this.keyId = idOf(base64Key);
            all.put(keyId, key);
        }
        if (previousKeys != null && !previousKeys.isBlank()) {
            for (String k : previousKeys.split(",")) {
                if (!k.isBlank()) {
                    all.putIfAbsent(idOf(k), parse(k));
                }
            }
        }
    }

    private static SecretKeySpec parse(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != 32) {
            throw new IllegalStateException("schoomano.secrets.key must be 32 bytes, base64 encoded");
        }
        return new SecretKeySpec(raw, "AES");
    }

    /** A short public label for a key (the start of its SHA-256), stored with each sealed value so the right key is found. */
    private static String idOf(String base64) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(base64.trim()));
            return java.util.HexFormat.of().formatHex(h, 0, 4);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when the value is not sealed under the current key (plain, written with the first format, or an older key). */
    public boolean needsRekey(String stored) {
        if (key == null || stored == null) {
            return false;
        }
        return !stored.startsWith(PREFIX + keyId + ":");
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
            return PREFIX + keyId + ":" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ct.length).put(nonce).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt", e);
        }
    }

    public String open(String stored) {
        if (stored == null || !(stored.startsWith(PREFIX) || stored.startsWith(FIRST))) {
            return stored;
        }
        if (all.isEmpty()) {
            throw new IllegalStateException("an encrypted secret is stored but schoomano.secrets.key is not set");
        }
        try {
            if (stored.startsWith(PREFIX)) {
                int colon = stored.indexOf(':', PREFIX.length());
                SecretKeySpec k = colon < 0 ? null : all.get(stored.substring(PREFIX.length(), colon));
                if (k == null) {
                    throw new IllegalStateException("could not decrypt (no key with that id: wrong key?)");
                }
                return decrypt(k, stored.substring(colon + 1));
            }
            for (SecretKeySpec k : all.values()) {
                try {
                    return decrypt(k, stored.substring(FIRST.length()));
                } catch (GeneralSecurityException e) {
                    // try the next key
                }
            }
            throw new IllegalStateException("could not decrypt (wrong key?)");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("could not decrypt (wrong key?)", e);
        }
    }

    private static String decrypt(SecretKeySpec k, String body) throws GeneralSecurityException {
        byte[] data = Base64.getDecoder().decode(body);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, data, 0, 12));
        return new String(c.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
    }
}
