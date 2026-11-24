package com.schoomano.domain.attendance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Fingerprint of an attendance batch, independent of the order of its rows and of the device clock, so a phone that
 * retries the same batch with the same idempotency key is recognised, and a key reused for different content is not.
 */
public final class BatchFingerprint {

    public record Row(UUID studentId, AttendanceStatus status, int baseVersion) { }

    private BatchFingerprint() { }

    public static String of(UUID sectionId, java.time.LocalDate date, List<Row> rows) {
        StringBuilder text = new StringBuilder().append(sectionId).append('|').append(date);
        rows.stream().sorted(Comparator.comparing(r -> r.studentId().toString()))
            .forEach(r -> text.append('\n').append(r.studentId()).append(':').append(r.status()).append(':').append(r.baseVersion()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Duplicate students in one batch are a client bug; the API refuses them rather than guessing which wins. */
    public static List<UUID> duplicates(List<Row> rows) {
        return rows.stream().map(Row::studentId).collect(java.util.stream.Collectors.groupingBy(u -> u, java.util.stream.Collectors.counting()))
            .entrySet().stream().filter(e -> e.getValue() > 1).map(java.util.Map.Entry::getKey).toList();
    }
}
