package com.schoomano.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.config.ConfigChange;
import com.schoomano.domain.config.ConfigEntry;
import com.schoomano.domain.config.ConfigResolver;
import com.schoomano.domain.tenancy.ScopeLevel;
import com.schoomano.domain.tenancy.ScopeNode;
import com.schoomano.domain.tenancy.ScopePath;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Effective-dated configuration and feature flags (keys prefixed "flag."). Draft → preview → publish.
 * Publishing never edits history: it closes the open version at the new effective date.
 */
@RestController
@RequestMapping("/api/v1/config")
public class ConfigController {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_.]{2,80}$");

    private final TenantTx tenantTx;
    private final ScopeDirectory scopes;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final ObjectMapper json;
    private final Clock clock;

    public ConfigController(TenantTx tenantTx, ScopeDirectory scopes, Authorizer authz, AuditWriter audit,
                            ObjectMapper json, Clock clock) {
        this.tenantTx = tenantTx;
        this.scopes = scopes;
        this.authz = authz;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public record ResolvedView(String key, JsonNode value, int version, String definedAtLevel, UUID definedAtId,
                               boolean inherited, Instant asOf) { }
    public record VersionView(UUID id, String scopeLevel, UUID scopeId, int version, JsonNode value,
                              Instant effectiveFrom, Instant effectiveTo, String status, String changeNote,
                              Instant publishedAt) { }
    public record DraftRequest(@NotNull ScopeLevel scopeLevel, @NotNull UUID scopeId, @NotNull JsonNode value,
                               @NotNull OffsetDateTime effectiveFrom, @NotBlank @Size(max = 500) String changeNote) { }
    public record PreviewRow(UUID campusId, String campusName, JsonNode before, JsonNode after, boolean changed) { }

    @GetMapping("/{key}")
    public ResolvedView resolve(RequestContext ctx, @PathVariable String key,
                                @RequestParam(required = false) UUID campusId,
                                @RequestParam(required = false) OffsetDateTime asOf) {
        validKey(key);
        Instant at = asOf == null ? clock.instant() : asOf.toInstant();
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath target = campusId == null ? ScopePath.group(ctx.tenant())
                : scopes.campusPath(jdbc, ctx.tenant(), campusId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.CONFIG_READ, Resource.ordinary("config", null, target, null));
            var resolved = ConfigResolver.resolve(entries(jdbc, key), key, target, at).orElseThrow(ApiException::notFound);
            return new ResolvedView(key, parse(resolved.valueJson()), resolved.version(),
                resolved.definedAt().level().name(), resolved.definedAt().id(), resolved.inherited(), at);
        });
    }

    @GetMapping("/{key}/versions")
    public List<VersionView> history(RequestContext ctx, @PathVariable String key) {
        validKey(key);
        return tenantTx.run(ctx.tenant(), jdbc -> {
            authz.requireOrNotFound(jdbc, ctx, Permission.CONFIG_READ,
                Resource.ordinary("config", null, ScopePath.group(ctx.tenant()), null));
            return jdbc.query("""
                select id, scope_level, scope_id, version, value::text, effective_from, effective_to, status,
                       change_note, published_at
                  from config_version where config_key = ? order by scope_level, scope_id, version
                """, (rs, i) -> new VersionView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                    rs.getInt(4), parse(rs.getString(5)), instant(rs, 6), instant(rs, 7), rs.getString(8),
                    rs.getString(9), instant(rs, 10)), key);
        });
    }

    @PostMapping("/{key}/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionView createDraft(RequestContext ctx, @PathVariable String key, @Valid @RequestBody DraftRequest req) {
        validKey(key);
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopeNode node = new ScopeNode(req.scopeLevel(), req.scopeId());
            ScopePath path = scopes.forNode(jdbc, ctx.tenant(), node);
            authz.requireOrForbidden(jdbc, ctx, Permission.CONFIG_PUBLISH, Resource.ordinary("config", null, path, null));
            Integer next = jdbc.queryForObject("""
                select coalesce(max(version), 0) + 1 from config_version
                 where config_key = ? and scope_level = ? and scope_id = ?
                """, Integer.class, key, node.level().name(), node.id());
            UUID id = jdbc.queryForObject("""
                insert into config_version (tenant_id, config_key, scope_level, scope_id, version, value,
                                            effective_from, change_note, created_by)
                values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), key, node.level().name(), node.id(), next,
                req.value().toString(), req.effectiveFrom(), req.changeNote(), ctx.actor().personId());
            audit.write(jdbc, ctx, "config.draft_created", "config_version", id.toString(), "SUCCESS", null,
                Map.of("key", key, "version", next, "scopeLevel", node.level().name()));
            return version(jdbc, id);
        });
    }

    @PostMapping("/drafts/{id}/preview")
    public List<PreviewRow> preview(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ConfigEntry draft = draftEntry(jdbc, ctx, id);
            List<UUID> campusIds = jdbc.queryForList("select id from campus order by name", UUID.class);
            List<ScopePath> targets = campusIds.stream()
                .map(c -> scopes.campusPath(jdbc, ctx.tenant(), c).orElseThrow()).toList();
            List<ConfigChange> changes = ConfigResolver.preview(entries(jdbc, draft.key()), draft, targets,
                draft.effectiveFrom());
            return changes.stream().map(ch -> {
                UUID campus = ch.target().leaf().id();
                String name = jdbc.queryForObject("select name from campus where id = ?", String.class, campus);
                return new PreviewRow(campus, name, parse(ch.before()), parse(ch.after()), ch.changed());
            }).toList();
        });
    }

    @PostMapping("/drafts/{id}/publish")
    public VersionView publish(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ConfigEntry draft = draftEntry(jdbc, ctx, id);
            try {
                jdbc.queryForObject("select config_publish(?, ?)::text", String.class, id, ctx.actor().personId());
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "publish_rejected",
                    "This version cannot be published. Its effective date must be later than the latest published version.");
            }
            audit.write(jdbc, ctx, "config.published", "config_version", id.toString(), "SUCCESS", null,
                Map.of("key", draft.key(), "version", draft.version()));
            jdbc.update("""
                insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                values (?, 'config', ?, 'config.published', ?::jsonb)
                """, ctx.tenant().value(), draft.key(),
                json.createObjectNode().put("key", draft.key()).put("version", draft.version()).toString());
            return version(jdbc, id);
        });
    }

    private ConfigEntry draftEntry(JdbcTemplate jdbc, RequestContext ctx, UUID id) {
        List<ConfigEntry> found = jdbc.query("""
            select config_key, scope_level, scope_id, version, value::text, effective_from, effective_to, status
              from config_version where id = ?
            """, (rs, i) -> entry(rs), id);
        ConfigEntry e = found.stream().findFirst().orElseThrow(ApiException::notFound);
        ScopePath path = scopes.forNode(jdbc, ctx.tenant(), e.scope());
        authz.requireOrNotFound(jdbc, ctx, Permission.CONFIG_PUBLISH, Resource.ordinary("config", id, path, null));
        if (e.status() != ConfigEntry.Status.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "not_draft", "Only draft versions can be previewed or published.");
        }
        return e;
    }

    private static List<ConfigEntry> entries(JdbcTemplate jdbc, String key) {
        return jdbc.query("""
            select config_key, scope_level, scope_id, version, value::text, effective_from, effective_to, status
              from config_version where config_key = ?
            """, (rs, i) -> entry(rs), key);
    }

    private static ConfigEntry entry(ResultSet rs) throws SQLException {
        return new ConfigEntry(rs.getString(1), new ScopeNode(ScopeLevel.valueOf(rs.getString(2)), rs.getObject(3, UUID.class)),
            rs.getInt(4), rs.getString(5), instant(rs, 6), instant(rs, 7), ConfigEntry.Status.valueOf(rs.getString(8)));
    }

    private VersionView version(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("""
            select id, scope_level, scope_id, version, value::text, effective_from, effective_to, status,
                   change_note, published_at from config_version where id = ?
            """, (rs, i) -> new VersionView(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class),
                rs.getInt(4), parse(rs.getString(5)), instant(rs, 6), instant(rs, 7), rs.getString(8),
                rs.getString(9), instant(rs, 10)), id);
    }

    private JsonNode parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return json.readTree(value);
        } catch (Exception e) {
            throw new IllegalStateException("stored config value is not JSON", e);
        }
    }

    private static Instant instant(ResultSet rs, int col) throws SQLException {
        OffsetDateTime t = rs.getObject(col, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static void validKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw ApiException.badRequest("invalid_key", "Configuration keys use lowercase letters, digits, dots and underscores.");
        }
    }
}
