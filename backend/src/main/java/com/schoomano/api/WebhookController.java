package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import com.schoomano.platform.WebhookUrls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.security.SecureRandom;
import java.sql.Array;
import java.util.Base64;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M27 webhooks. A group administrator registers an https address for the whole school and the kinds of event it wants;
 * each event is then posted, signed, with retries. Events about individual students are not offered until the legal
 * review of personal data leaving the system is done.
 */
@RestController
@RequestMapping("/api/v1")
public class WebhookController {

    /** The events an endpoint may ask for. None of them names or identifies a student. */
    public static final List<String> EVENT_TYPES = List.of("academic.year_rolled_over", "config.published", "import.committed",
        "timetable.published", "timetable.substitution_created", "timetable.substitution_cancelled");

    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;

    public WebhookController(TenantTx tenantTx, Authorizer authz, AuditWriter audit) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
    }

    public record EndpointCreate(@NotBlank @Size(min = 3, max = 80) String name, @NotBlank @Size(max = 500) String url,
                                 @NotEmpty @Size(max = 20) List<String> eventTypes) { }

    public record EndpointView(UUID id, String name, String url, List<String> eventTypes, boolean active, int failures,
                               OffsetDateTime createdAt, OffsetDateTime disabledAt, String disabledReason) { }

    /** The only response that carries the signing secret. */
    public record EndpointIssued(EndpointView endpoint, String secret) { }

    public record DeliveryView(UUID id, String eventType, String status, int attempts, Integer lastStatus, String lastError,
                               OffsetDateTime createdAt, OffsetDateTime sentAt) { }

    public record Ping(UUID deliveryId) { }

    @GetMapping("/webhooks/event-types")
    public List<String> eventTypes(RequestContext ctx) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            return EVENT_TYPES;
        });
    }

    @GetMapping("/webhooks")
    public List<EndpointView> list(RequestContext ctx) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            return endpoints(jdbc, "true");
        });
    }

    @PostMapping("/webhooks")
    @ResponseStatus(HttpStatus.CREATED)
    public EndpointIssued create(RequestContext ctx, @Valid @RequestBody EndpointCreate req) {
        String problem = WebhookUrls.problem(req.url());
        if (problem != null) {
            throw ApiException.badRequest("bad_url", problem);
        }
        Set<String> types = new LinkedHashSet<>(req.eventTypes());
        if (!EVENT_TYPES.containsAll(types)) {
            throw ApiException.badRequest("unknown_event_type", "Choose from the event types the school offers.");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            String secret = "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            UUID id = jdbc.queryForObject("""
                insert into webhook_endpoint (tenant_id, name, url, secret, event_types, created_by)
                values (?, ?, ?, ?, ?::text[], ?) returning id
                """, UUID.class, ctx.tenant().value(), req.name().trim(), req.url().trim(), secret,
                "{" + String.join(",", types) + "}", ctx.actor().personId());
            audit.write(jdbc, ctx, "webhook.created", "webhook_endpoint", id.toString(), "SUCCESS", null, Map.of("eventTypes", types.size()));
            return new EndpointIssued(endpoints(jdbc, "e.id = ?", id).get(0), secret);
        });
    }

    @PostMapping("/webhooks/{id}/disable")
    public EndpointView disable(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            if (endpoints(jdbc, "e.id = ?", id).isEmpty()) {
                throw ApiException.notFound();
            }
            if (jdbc.update("update webhook_endpoint set active = false, disabled_at = now(), disabled_reason = 'Switched off by an administrator' where id = ? and active", id) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_off", "This endpoint is already switched off.");
            }
            audit.write(jdbc, ctx, "webhook.disabled", "webhook_endpoint", id.toString(), "SUCCESS", null, Map.of());
            return endpoints(jdbc, "e.id = ?", id).get(0);
        });
    }

    @PostMapping("/webhooks/{id}/ping")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Ping ping(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            UUID delivery = jdbc.queryForObject("select webhook_ping(?)", UUID.class, id);
            if (delivery == null) {
                throw ApiException.notFound();
            }
            return new Ping(delivery);
        });
    }

    @GetMapping("/webhooks/{id}/deliveries")
    public List<DeliveryView> deliveries(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx);
            if (endpoints(jdbc, "e.id = ?", id).isEmpty()) {
                throw ApiException.notFound();
            }
            return jdbc.query("""
                select d.id, ev.event_type, d.status, d.attempts, d.last_status, d.last_error, d.created_at, d.sent_at
                  from webhook_delivery d join outbox_event ev on ev.id = d.outbox_event_id
                 where d.endpoint_id = ? order by d.created_at desc limit 50
                """, (rs, i) -> new DeliveryView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4),
                    (Integer) rs.getObject(5), rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class)), id);
        });
    }

    private void require(JdbcTemplate jdbc, RequestContext ctx) {
        authz.requireOrNotFound(jdbc, ctx, Permission.WEBHOOK_MANAGE, Resource.ordinary("webhook", null, ScopePath.group(ctx.tenant()), null));
    }

    private List<EndpointView> endpoints(JdbcTemplate jdbc, String where, Object... args) {
        return jdbc.query("""
            select e.id, e.name, e.url, e.event_types, e.active, e.consecutive_failures, e.created_at, e.disabled_at, e.disabled_reason
              from webhook_endpoint e where """ + " " + where + " order by e.created_at desc limit 100",
            (rs, i) -> {
                Array types = rs.getArray(4);
                return new EndpointView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), List.of((String[]) types.getArray()),
                    rs.getBoolean(5), rs.getInt(6), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), rs.getString(9));
            }, args);
    }
}
