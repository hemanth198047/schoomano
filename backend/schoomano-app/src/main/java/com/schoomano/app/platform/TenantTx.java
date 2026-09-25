package com.schoomano.app.platform;

import com.schoomano.core.tenancy.TenantId;
import java.util.function.Function;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The only way application code touches tenant data. Opens a transaction and sets
 * {@code app.tenant_id} (transaction-local) so PostgreSQL RLS confines every statement.
 * The tenant always comes from the resolved server-side actor, never from request input.
 */
@Component
public class TenantTx {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public TenantTx(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
    }

    public <T> T run(TenantId tenant, Function<JdbcTemplate, T> work) {
        return tx.execute(status -> {
            jdbc.queryForObject("select set_config('app.tenant_id', ?, true)", String.class, tenant.toString());
            MDC.put("tenantId", tenant.toString());
            return work.apply(jdbc);
        });
    }

    /** For the pre-tenant login lookup only (auth_resolve_identity is SECURITY DEFINER). */
    public <T> T runWithoutTenant(Function<JdbcTemplate, T> work) {
        return tx.execute(status -> work.apply(jdbc));
    }
}
