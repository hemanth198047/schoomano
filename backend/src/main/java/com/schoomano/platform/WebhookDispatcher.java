package com.schoomano.platform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends outbox events to registered webhook endpoints. The database decides which endpoint gets which event, leases each
 * delivery to one sender at a time and schedules retries; this class only signs and posts. Redirects are never followed,
 * every address is resolved and refused when it is not on the public internet, and a response body is never kept.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int BATCH = 20;

    private record Due(UUID id, String url, String secret, String previousSecret, UUID eventId, String type, OffsetDateTime occurredAt, UUID tenantId, String payload) { }

    /** What happened to one attempt: an HTTP status, or 0 with a short reason. */
    record Outcome(int status, String error) { }

    private final TenantTx tx;
    private final SecretBox secrets;
    private final ObjectMapper json;
    private final boolean enabled;
    private final boolean allowPrivate;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookDispatcher(TenantTx tx, SecretBox secrets, ObjectMapper json, @Value("${schoomano.webhooks.dispatcher:true}") boolean enabled,
                             @Value("${schoomano.webhooks.allow-private:false}") boolean allowPrivate) {
        this.tx = tx;
        this.secrets = secrets;
        this.json = json;
        this.enabled = enabled;
        this.allowPrivate = allowPrivate;
    }

    @Scheduled(fixedDelayString = "${schoomano.webhooks.poll-ms:10000}", initialDelayString = "${schoomano.webhooks.poll-ms:10000}")
    void tick() {
        if (!enabled) {
            return;
        }
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("webhook dispatch failed", e);
        }
    }

    /** One pass: fan new events out to endpoints, then send what is due. Returns the number of attempts made. */
    public int runOnce() {
        tx.runWithoutTenant(jdbc -> jdbc.queryForObject("select webhook_fan_out(?)", Integer.class, 200));
        List<Due> due = tx.runWithoutTenant(jdbc -> jdbc.query(
            "select delivery_id, url, secret, previous_secret, event_id, event_type, occurred_at, tenant_id, payload::text from webhook_claim_due(?)",
            (rs, i) -> new Due(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, UUID.class), rs.getString(6),
                rs.getObject(7, OffsetDateTime.class), rs.getObject(8, UUID.class), rs.getString(9)), BATCH));
        for (Due d : due) {
            Outcome o;
            try {
                List<String> keys = d.previousSecret() == null ? List.of(secrets.open(d.secret()))
                    : List.of(secrets.open(d.secret()), secrets.open(d.previousSecret()));
                o = post(d.url(), keys, d.id(), d.type(), body(d));
            } catch (RuntimeException e) {
                o = new Outcome(0, e.getClass().getSimpleName());
            }
            Outcome result = o;
            tx.runWithoutTenant(jdbc -> jdbc.queryForList("select webhook_mark(?, ?, ?)", d.id(), result.status(), result.error()));
        }
        return due.size();
    }

    private String body(Due d) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", d.eventId());
        envelope.put("type", d.type());
        envelope.put("occurredAt", d.occurredAt().toInstant().toString());
        envelope.put("tenantId", d.tenantId());
        try {
            envelope.put("data", json.readTree(d.payload()));
            return json.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("event payload is not valid JSON", e);
        }
    }

    /** Posts one signed event. Package-visible so a test can aim it at a local receiver. */
    Outcome post(String url, List<String> secretsInUse, UUID deliveryId, String type, String body) {
        URI uri = URI.create(url);
        if (!allowPrivate && !publicHost(uri.getHost())) {
            return new Outcome(0, "address_not_public");
        }
        long now = System.currentTimeMillis() / 1000;
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
            .header("Content-Type", "application/json")
            .header("User-Agent", "Schoomano-Webhooks/1")
            .header("X-Schoomano-Event", type)
            .header("X-Schoomano-Delivery", deliveryId.toString())
            .header("X-Schoomano-Signature", WebhookSigner.header(secretsInUse, now, body))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        try {
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            return new Outcome(status, status >= 200 && status < 300 ? null : "http_" + status);
        } catch (IOException e) {
            return new Outcome(0, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(0, "interrupted");
        }
    }

    private static boolean publicHost(String host) {
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (!WebhookUrls.isPublic(a)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
