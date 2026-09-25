package com.schoomano.core.config;

import com.schoomano.core.tenancy.ScopeNode;
import com.schoomano.core.tenancy.ScopePath;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Resolves effective-dated, inherited configuration. The narrowest scope in the target's path wins;
 * within a scope, the published version whose window covers {@code asOf} wins. History is never
 * rewritten, so a report computed for a past date re-resolves the value that applied then.
 * Rollback is "publish a new version carrying the old value" — it never deletes history.
 */
public final class ConfigResolver {

    private ConfigResolver() {
    }

    public static Optional<ResolvedConfig> resolve(Collection<ConfigEntry> entries, String key,
                                                   ScopePath target, Instant asOf) {
        List<ScopeNode> path = target.nodes();
        for (int i = path.size() - 1; i >= 0; i--) {
            ScopeNode node = path.get(i);
            Optional<ConfigEntry> hit = entries.stream()
                .filter(e -> e.status() == ConfigEntry.Status.PUBLISHED)
                .filter(e -> e.key().equals(key) && e.scope().equals(node))
                .filter(e -> e.coversInstant(asOf))
                .max(Comparator.comparingInt(ConfigEntry::version));
            if (hit.isPresent()) {
                ConfigEntry e = hit.get();
                return Optional.of(new ResolvedConfig(key, e.valueJson(), e.version(), node, i < path.size() - 1));
            }
        }
        return Optional.empty();
    }

    /**
     * Preview what each target would see at {@code asOf} if {@code draft} were published now,
     * applying the same close-the-open-version rule as the database's config_publish().
     */
    public static List<ConfigChange> preview(Collection<ConfigEntry> entries, ConfigEntry draft,
                                             List<ScopePath> targets, Instant asOf) {
        List<ConfigEntry> after = new ArrayList<>();
        for (ConfigEntry e : entries) {
            boolean sameSeries = e.status() == ConfigEntry.Status.PUBLISHED
                && e.key().equals(draft.key()) && e.scope().equals(draft.scope()) && e.effectiveTo() == null;
            after.add(sameSeries
                ? new ConfigEntry(e.key(), e.scope(), e.version(), e.valueJson(), e.effectiveFrom(),
                    draft.effectiveFrom(), e.status())
                : e);
        }
        after.add(new ConfigEntry(draft.key(), draft.scope(), draft.version(), draft.valueJson(),
            draft.effectiveFrom(), draft.effectiveTo(), ConfigEntry.Status.PUBLISHED));

        List<ConfigChange> changes = new ArrayList<>();
        for (ScopePath t : targets) {
            String before = resolve(entries, draft.key(), t, asOf).map(ResolvedConfig::valueJson).orElse(null);
            String next = resolve(after, draft.key(), t, asOf).map(ResolvedConfig::valueJson).orElse(null);
            changes.add(new ConfigChange(t, before, next));
        }
        return changes;
    }
}
