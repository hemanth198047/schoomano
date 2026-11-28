package com.schoomano.domain.documents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * What a school may upload as a document: a PDF, PNG or JPEG of at most 5 MB. The type is decided from the first bytes of the
 * file, never from what the sender says, and the file name is cleaned so it can be shown and downloaded safely.
 */
public final class UploadRules {

    public static final int MAX_BYTES = 5 * 1024 * 1024;

    private UploadRules() { }

    /** The content type read from the bytes, or empty when it is not one of the accepted kinds. */
    public static Optional<String> typeOf(byte[] b) {
        if (b == null || b.length < 4) {
            return Optional.empty();
        }
        if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return Optional.of("application/pdf");
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G' && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return Optional.of("image/png");
        }
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        return Optional.empty();
    }

    /** The extension a type is given when its name has none that matches. */
    public static String extensionOf(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            default -> "jpg";
        };
    }

    /**
     * A plain file name: no folders, no control characters or characters that break a header, at most 120 characters, and an
     * extension that matches the real type.
     */
    public static String cleanName(String raw, String contentType) {
        String name = raw == null ? "" : raw;
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}\"<>:*?|;]", "").replaceAll("\\s+", " ").trim();
        while (name.startsWith(".")) {
            name = name.substring(1);
        }
        String ext = extensionOf(contentType);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String have = dot > 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
        boolean matches = have.equals(ext) || (ext.equals("jpg") && have.equals("jpeg"));
        if (stem.isBlank()) {
            stem = "document";
        }
        String base = matches ? stem + "." + have : stem + "." + ext;
        if (base.length() > 120) {
            base = stem.substring(0, Math.max(1, 120 - ext.length() - 1)) + "." + ext;
        }
        return base;
    }

    public static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The header value for a download: always an attachment, with the name quoted for old clients and encoded for new ones. */
    public static String contentDisposition(String name) {
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_");
        String encoded = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
    }
}
