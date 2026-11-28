package com.schoomano.api;

import com.schoomano.platform.TenantTx;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Blanks the parsed files and per-row values of settled imports once they are past retention (see migration V37).
 * Committed imports are kept for the rollback window; discarded, rolled-back and never-committed ones go sooner.
 */
@Component
public class ImportPurger {

    private static final Logger log = LoggerFactory.getLogger(ImportPurger.class);

    private final TenantTx tx;
    private final boolean enabled;
    private final int committedDays;
    private final int openDays;

    public ImportPurger(TenantTx tx, @Value("${schoomano.imports.purger:true}") boolean enabled,
                        @Value("${schoomano.imports.committed-retention-days:90}") int committedDays,
                        @Value("${schoomano.imports.open-retention-days:7}") int openDays) {
        this.tx = tx;
        this.enabled = enabled;
        this.committedDays = committedDays;
        this.openDays = openDays;
    }

    @Scheduled(fixedDelayString = "${schoomano.imports.purge-poll-ms:3600000}", initialDelayString = "${schoomano.imports.purge-poll-ms:3600000}")
    void tick() {
        if (!enabled) {
            return;
        }
        try {
            runOnce(committedDays, openDays);
        } catch (RuntimeException e) {
            log.error("import purge failed", e);
        }
    }

    /** One pass with the given retention. Returns how many imports were purged. */
    public int runOnce(int committedRetentionDays, int openRetentionDays) {
        List<Integer> counts = tx.runWithoutTenant(jdbc -> jdbc.query("select purged from import_purge(?, ?)",
            (rs, i) -> rs.getInt(1), committedRetentionDays, openRetentionDays));
        int total = counts.stream().mapToInt(Integer::intValue).sum();
        if (total > 0) {
            log.info("purged {} settled imports", total);
        }
        return total;
    }
}
