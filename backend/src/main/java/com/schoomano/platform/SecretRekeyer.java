package com.schoomano.platform;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Changing the encryption key: with schoomano.secrets.rekey=true the application re-seals every stored webhook secret under
 * the current key at start-up, then carries on. The old key must be listed in schoomano.secrets.previous-keys while this
 * runs. Safe to run again and from several instances at once (a row is only replaced if it is unchanged since it was read).
 */
@Component
public class SecretRekeyer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecretRekeyer.class);

    public record Result(int resealed, int alreadyCurrent, int skipped) { }

    private record Row(UUID id, String secret, String previous) { }

    private final TenantTx tx;
    private final SecretBox box;
    private final boolean enabled;

    public SecretRekeyer(TenantTx tx, SecretBox box, @Value("${schoomano.secrets.rekey:false}") boolean enabled) {
        this.tx = tx;
        this.box = box;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        if (!box.encrypting()) {
            throw new IllegalStateException("schoomano.secrets.rekey needs schoomano.secrets.key");
        }
        Result r = rekey();
        log.info("secret rekey finished: {} re-sealed, {} already under the current key, {} changed while running and left for the next run",
            r.resealed(), r.alreadyCurrent(), r.skipped());
    }

    public Result rekey() {
        List<Row> rows = tx.runWithoutTenant(jdbc -> jdbc.query("select endpoint_id, secret, previous_secret from webhook_secrets_all()",
            (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))));
        int done = 0;
        int current = 0;
        int skipped = 0;
        for (Row row : rows) {
            if (!box.needsRekey(row.secret()) && !box.needsRekey(row.previous())) {
                current++;
                continue;
            }
            String secret = box.seal(box.open(row.secret()));
            String previous = row.previous() == null ? null : box.seal(box.open(row.previous()));
            Boolean ok = tx.runWithoutTenant(jdbc -> jdbc.queryForObject("select webhook_secret_rekey(?, ?, ?, ?, ?)", Boolean.class,
                row.id(), row.secret(), row.previous(), secret, previous));
            if (Boolean.TRUE.equals(ok)) {
                done++;
            } else {
                skipped++;
            }
        }
        return new Result(done, current, skipped);
    }
}
