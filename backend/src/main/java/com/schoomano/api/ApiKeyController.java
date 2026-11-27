package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import com.schoomano.security.ApiKeyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
 * M27 API keys. A campus administrator creates a read-only key for an outside system (a library, a canteen, a data
 * warehouse), sees the secret once, and can revoke it at once. A key can read students' basic details, attendance,
 * timetable and campus information of its own campus, and nothing else.
 */
@RestController
@RequestMapping("/api/v1")
public class ApiKeyController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ApiKeyService keys;

    public ApiKeyController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ApiKeyService keys) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.keys = keys;
    }

    public record KeyCreate(@NotBlank @Size(min = 3, max = 80) String name, @Min(1) @Max(730) Integer validDays) { }

    public record KeyView(UUID id, String name, String hint, OffsetDateTime createdAt, OffsetDateTime expiresAt, OffsetDateTime lastUsedAt,
                          OffsetDateTime revokedAt, String status, String createdBy) { }

    /** The only response that carries the secret. It cannot be shown again. */
    public record KeyIssued(KeyView key, String secret) { }

    @PostMapping("/campuses/{campusId}/api-keys")
    @ResponseStatus(HttpStatus.CREATED)
    public KeyIssued create(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody KeyCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, campusId);
            ApiKeyService.Issued issued = keys.issue();
            int days = req.validDays() == null ? 365 : req.validDays();
            UUID id = jdbc.queryForObject("select api_key_create(?, ?, ?, ?, now() + (? * interval '1 day'), ?)", UUID.class,
                campusId, req.name().trim(), issued.hash(), issued.hint(), days, ctx.actor().personId());
            audit.write(jdbc, ctx, "apikey.created", "api_key", id.toString(), "SUCCESS", null, Map.of("validDays", days));
            return new KeyIssued(list(jdbc, "k.id = ?", id).get(0), issued.secret());
        });
    }

    @GetMapping("/campuses/{campusId}/api-keys")
    public List<KeyView> list(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, campusId);
            return list(jdbc, "k.campus_id = ?", campusId);
        });
    }

    @PostMapping("/api-keys/{id}/revoke")
    public KeyView revoke(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            UUID campusId = jdbc.queryForList("select campus_id from api_key where id = ?", UUID.class, id).stream().findFirst().orElseThrow(ApiException::notFound);
            require(jdbc, ctx, campusId);
            if (!Boolean.TRUE.equals(jdbc.queryForObject("select api_key_revoke(?, ?)", Boolean.class, id, ctx.actor().personId()))) {
                throw new ApiException(HttpStatus.CONFLICT, "already_revoked", "This key is already revoked.");
            }
            audit.write(jdbc, ctx, "apikey.revoked", "api_key", id.toString(), "SUCCESS", null, Map.of());
            return list(jdbc, "k.id = ?", id).get(0);
        });
    }

    private void require(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, Permission.APIKEY_MANAGE, Resource.ordinary("api_key", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private List<KeyView> list(JdbcTemplate jdbc, String where, Object... args) {
        return jdbc.query("""
            select k.id, k.name, k.token_hint, k.created_at, k.expires_at, k.last_used_at, k.revoked_at,
                   case when k.revoked_at is not null then 'REVOKED' when k.expires_at <= now() then 'EXPIRED' else 'ACTIVE' end,
                   trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, ''))
              from api_key k left join person p on p.tenant_id = k.tenant_id and p.id = k.created_by
             where """ + " " + where + " order by k.created_at desc limit 200",
            (rs, i) -> new KeyView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class),
                rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class), rs.getString(8), rs.getString(9)), args);
    }
}
