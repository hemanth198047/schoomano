package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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
 * M24 compliance register. A campus lists its obligations with an owner and a next due date; evidence is recorded
 * against an item and never edited, and moves the due date forward. This is a register of what the school says it
 * must do; the legal content of those obligations is not decided here.
 */
@RestController
@RequestMapping("/api/v1")
public class ComplianceController {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int SOON_DAYS = 30;

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;

    public ComplianceController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    public record ItemCreate(@NotBlank @Size(min = 3, max = 160) String title,
                             @NotNull @Pattern(regexp = "FIRE_SAFETY|HEALTH_SAFETY|PRIVACY|SAFEGUARDING|FINANCE|STATUTORY|OTHER") String category,
                             @NotBlank @Size(min = 2, max = 80) String ownerName, @Min(1) @Max(60) Integer repeatMonths, @NotNull LocalDate firstDueOn) { }

    public record ItemView(UUID id, String title, String category, String ownerName, Integer repeatMonths, LocalDate nextDueOn, String status,
                           String standing, LocalDate lastDoneOn, String lastReference, int evidenceCount) { }

    public record EvidenceCreate(@NotNull LocalDate doneOn, @Size(min = 2, max = 200) String reference, @Size(max = 1000) String note, LocalDate validUntil) { }

    public record EvidenceView(UUID id, LocalDate doneOn, String reference, String note, LocalDate validUntil, String recordedBy, OffsetDateTime recordedAt) { }

    @PostMapping("/campuses/{campusId}/compliance-items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView create(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody ItemCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.COMPLIANCE_MANAGE, campusId);
            try {
                UUID id = jdbc.queryForObject("""
                    insert into compliance_item (tenant_id, campus_id, title, category, owner_name, repeat_months, next_due_on, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), campusId, req.title().trim(), req.category(), req.ownerName().trim(),
                    req.repeatMonths(), req.firstDueOn(), ctx.actor().personId());
                audit.write(jdbc, ctx, "compliance.item_added", "compliance_item", id.toString(), "SUCCESS", null, Map.of("category", req.category()));
                return items(jdbc, "i.id = ?", id).get(0);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "duplicate_item", "This campus already has an item with that title.");
            }
        });
    }

    @GetMapping("/campuses/{campusId}/compliance-items")
    public List<ItemView> list(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.COMPLIANCE_READ, campusId);
            return items(jdbc, "i.campus_id = ?", campusId);
        });
    }

    @PostMapping("/compliance-items/{id}/retire")
    public ItemView retire(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.COMPLIANCE_MANAGE, itemCampus(jdbc, id));
            if (jdbc.update("update compliance_item set status = 'RETIRED' where id = ? and status = 'ACTIVE'", id) == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "already_retired", "This item is already retired.");
            }
            audit.write(jdbc, ctx, "compliance.item_retired", "compliance_item", id.toString(), "SUCCESS", null, Map.of());
            return items(jdbc, "i.id = ?", id).get(0);
        });
    }

    @PostMapping("/compliance-items/{id}/evidence")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView record(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody EvidenceCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.COMPLIANCE_EVIDENCE, itemCampus(jdbc, id));
            Integer repeat = jdbc.queryForObject("select repeat_months from compliance_item where id = ?", Integer.class, id);
            LocalDate current = jdbc.query("select next_due_on from compliance_item where id = ?", (rs, i) -> rs.getObject(1, LocalDate.class), id).get(0);
            try {
                jdbc.update("""
                    insert into compliance_evidence (tenant_id, item_id, done_on, reference, note, valid_until, recorded_by) values (?, ?, ?, ?, ?, ?, ?)
                    """, ctx.tenant().value(), id, req.doneOn(), blank(req.reference()), blank(req.note()), req.validUntil(), ctx.actor().personId());
            } catch (DataIntegrityViolationException e) {
                String m = String.valueOf(e.getMostSpecificCause().getMessage());
                if (m.contains("retired")) {
                    throw new ApiException(HttpStatus.CONFLICT, "item_retired", "This item is retired.");
                }
                throw ApiException.badRequest("invalid_evidence", "The date done cannot be in the future and the expiry cannot be before it.");
            }
            // The due date moves to the expiry if given, else by the repeat interval; a one-off item with neither is done.
            LocalDate proposed = req.validUntil() != null ? req.validUntil() : repeat != null ? req.doneOn().plusMonths(repeat) : null;
            if (proposed == null) {
                jdbc.update("update compliance_item set next_due_on = null where id = ?", id);
            } else if (current == null || proposed.isAfter(current)) {
                jdbc.update("update compliance_item set next_due_on = ? where id = ?", proposed, id);
            }
            audit.write(jdbc, ctx, "compliance.evidence_recorded", "compliance_item", id.toString(), "SUCCESS", null, Map.of());
            return items(jdbc, "i.id = ?", id).get(0);
        });
    }

    @GetMapping("/compliance-items/{id}/evidence")
    public List<EvidenceView> evidence(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            require(jdbc, ctx, Permission.COMPLIANCE_READ, itemCampus(jdbc, id));
            return jdbc.query("""
                select e.id, e.done_on, e.reference, e.note, e.valid_until,
                       trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), e.recorded_at
                  from compliance_evidence e join person p on p.tenant_id = e.tenant_id and p.id = e.recorded_by
                 where e.item_id = ? order by e.done_on desc, e.recorded_at desc
                """, (rs, i) -> new EvidenceView(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getString(4),
                    rs.getObject(5, LocalDate.class), rs.getString(6), rs.getObject(7, OffsetDateTime.class)), id);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private UUID itemCampus(JdbcTemplate jdbc, UUID id) {
        return jdbc.query("select campus_id from compliance_item where id = ?", (rs, i) -> rs.getObject(1, UUID.class), id)
            .stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private void require(JdbcTemplate jdbc, RequestContext ctx, Permission permission, UUID campusId) {
        authz.requireOrNotFound(jdbc, ctx, permission, Resource.ordinary("compliance", null, access.campusPath(jdbc, ctx, campusId), null));
    }

    private List<ItemView> items(JdbcTemplate jdbc, String where, Object arg) {
        LocalDate today = LocalDate.now(IST);
        return jdbc.query("""
            select i.id, i.title, i.category, i.owner_name, i.repeat_months, i.next_due_on, i.status,
                   (select max(e.done_on) from compliance_evidence e where e.item_id = i.id),
                   (select e.reference from compliance_evidence e where e.item_id = i.id order by e.done_on desc, e.recorded_at desc limit 1),
                   (select count(*) from compliance_evidence e where e.item_id = i.id)
              from compliance_item i
             where """ + " " + where + " order by i.status, i.next_due_on nulls last, i.title", (rs, n) -> {
            LocalDate due = rs.getObject(6, LocalDate.class);
            String status = rs.getString(7);
            String standing = "RETIRED".equals(status) ? "RETIRED" : due == null ? "DONE" : due.isBefore(today) ? "OVERDUE"
                : !due.isAfter(today.plusDays(SOON_DAYS)) ? "DUE_SOON" : "CURRENT";
            return new ItemView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), (Integer) rs.getObject(5), due, status,
                standing, rs.getObject(8, LocalDate.class), rs.getString(9), rs.getInt(10));
        }, arg);
    }
}
