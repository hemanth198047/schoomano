package com.schoomano.security;

import com.schoomano.domain.tenancy.TenantId;
import com.schoomano.platform.TenantTx;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells the school's principals and group administrators, in their inbox, the first time platform support uses an access
 * the school approved. Only the first use of each grant sends anything. It never holds up support or fails a request.
 */
@Component
public class SupportSignInNotifier {

    private static final Logger log = LoggerFactory.getLogger(SupportSignInNotifier.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));

    private final TenantTx tx;

    public SupportSignInNotifier(TenantTx tx) {
        this.tx = tx;
    }

    public void used(TenantId tenant, UUID grantId) {
        try {
            record First(String ref, OffsetDateTime until) { }
            List<First> first = tx.runWithoutTenant(jdbc -> jdbc.query("select support_user_ref, expires_at from support_first_use(?)",
                (rs, i) -> new First(rs.getString(1), rs.getObject(2, OffsetDateTime.class)), grantId));
            if (first.isEmpty()) {
                return;
            }
            First f = first.get(0);
            tx.run(tenant, jdbc -> {
                List<UUID> campuses = jdbc.queryForList("select id from campus order by created_at limit 1", UUID.class);
                List<UUID> people = jdbc.queryForList("""
                    select distinct person_id from role_grant
                     where role_key in ('principal', 'group_admin') and (valid_to is null or valid_to > now())
                    """, UUID.class);
                if (campuses.isEmpty() || people.isEmpty()) {
                    return null;
                }
                String literal = "{" + String.join(",", people.stream().map(UUID::toString).toList()) + "}";
                jdbc.queryForObject("select notice_tell_staff('SECURITY', ?, ?, ?, ?::uuid[], null)", Integer.class, campuses.get(0),
                    "Support has signed in",
                    f.ref() + " from platform support has entered the school under the access you approved. It ends at " + WHEN.format(f.until())
                        + ". Support can read the audit trail and operational health only, and everything it does is in the audit trail.",
                    literal);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("could not tell the school that support signed in", e);
        }
    }
}
