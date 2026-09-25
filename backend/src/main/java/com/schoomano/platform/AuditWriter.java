package com.schoomano.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.access.AccessPolicy;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes append-only audit events inside the caller's tenant transaction, so the audit row and the
 * change it describes commit or roll back together. Details must never contain health or case text.
 */
@Component
public class AuditWriter {

    private final ObjectMapper json;

    public AuditWriter(ObjectMapper json) {
        this.json = json;
    }

    public void write(JdbcTemplate jdbc, RequestContext ctx, String action, String resourceType, String resourceId,
                      String outcome, String reason, Map<String, ?> details) {
        String actorKind = ctx.actor().kind() == com.schoomano.domain.access.Actor.Kind.SUPPORT ? "SUPPORT" : "USER";
        jdbc.update("""
            insert into audit_event (tenant_id, actor_person_id, actor_kind, action, resource_type, resource_id,
                                     outcome, reason, correlation_id, policy_version, details)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
            """,
            ctx.tenant().value(), ctx.actor().personId(), actorKind, action, resourceType, resourceId,
            outcome, reason, CorrelationIdFilter.current(), AccessPolicy.POLICY_VERSION, toJson(details));
    }

    private String toJson(Map<String, ?> details) {
        try {
            return json.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit details not serialisable", e);
        }
    }

    public static String id(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }
}
