package com.schoomano.domain.config;

import static com.schoomano.domain.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigResolverTest {

    private static final String KEY = "attendance.late_after_minutes";
    private static final Instant TERM2 = Instant.parse("2026-10-01T00:00:00Z");

    private static ConfigEntry published(com.schoomano.domain.tenancy.ScopeNode scope, int v, String value,
                                         Instant from, Instant to) {
        return new ConfigEntry(KEY, scope, v, value, from, to, ConfigEntry.Status.PUBLISHED);
    }

    @Test
    void narrowestScopeWinsAndInheritanceIsReported() {
        var entries = List.of(
            published(GROUP_A, 1, "10", YEAR_START, null),
            published(SOUTH, 1, "15", YEAR_START, null));
        var north = ConfigResolver.resolve(entries, KEY, northPath(), NOW).orElseThrow();
        var south = ConfigResolver.resolve(entries, KEY, southPath(), NOW).orElseThrow();
        assertEquals("10", north.valueJson());
        assertTrue(north.inherited());
        assertEquals("15", south.valueJson());
        assertFalse(south.inherited());
    }

    @Test
    void midYearChangeKeepsOldValueForPastDates() {
        var entries = List.of(
            published(GROUP_A, 1, "10", YEAR_START, TERM2),
            published(GROUP_A, 2, "5", TERM2, null));
        assertEquals("10", ConfigResolver.resolve(entries, KEY, northPath(), Instant.parse("2026-07-15T03:30:00Z")).orElseThrow().valueJson());
        assertEquals("5", ConfigResolver.resolve(entries, KEY, northPath(), Instant.parse("2026-11-15T03:30:00Z")).orElseThrow().valueJson());
    }

    @Test
    void draftsAndRetiredVersionsAreIgnored() {
        var entries = List.of(
            published(GROUP_A, 1, "10", YEAR_START, null),
            new ConfigEntry(KEY, GROUP_A, 2, "1", YEAR_START, null, ConfigEntry.Status.DRAFT));
        assertEquals("10", ConfigResolver.resolve(entries, KEY, northPath(), NOW).orElseThrow().valueJson());
    }

    @Test
    void unknownKeyResolvesToEmpty() {
        assertTrue(ConfigResolver.resolve(List.of(), KEY, northPath(), NOW).isEmpty());
    }

    @Test
    void previewShowsWhichCampusesChangeAndWhichKeepAnOverride() {
        var entries = List.of(
            published(GROUP_A, 1, "10", YEAR_START, null),
            published(SOUTH, 1, "15", YEAR_START, null));
        var draft = new ConfigEntry(KEY, GROUP_A, 2, "5", TERM2, null, ConfigEntry.Status.DRAFT);
        var changes = ConfigResolver.preview(entries, draft, List.of(northPath(), southPath()), TERM2.plusSeconds(86400));
        assertEquals("10", changes.get(0).before());
        assertEquals("5", changes.get(0).after());
        assertTrue(changes.get(0).changed());
        assertFalse(changes.get(1).changed(), "South keeps its campus override");
    }

    @Test
    void rollbackIsANewVersionAndHistoryStaysIntact() {
        var entries = new ArrayList<>(List.of(
            published(GROUP_A, 1, "10", YEAR_START, TERM2),
            published(GROUP_A, 2, "5", TERM2, Instant.parse("2026-10-20T00:00:00Z"))));
        entries.add(published(GROUP_A, 3, "10", Instant.parse("2026-10-20T00:00:00Z"), null));
        assertEquals("5", ConfigResolver.resolve(entries, KEY, northPath(), Instant.parse("2026-10-10T00:00:00Z")).orElseThrow().valueJson());
        var now = ConfigResolver.resolve(entries, KEY, northPath(), Instant.parse("2026-11-01T00:00:00Z")).orElseThrow();
        assertEquals("10", now.valueJson());
        assertEquals(3, now.version());
    }

    @Test
    void invalidWindowIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> published(GROUP_A, 1, "10", TERM2, YEAR_START));
    }
}
