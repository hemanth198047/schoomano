package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M15 finance and procurement. Anyone with PROCUREMENT_REQUEST raises a purchase request against a budget; someone else
 * with PROCUREMENT_APPROVE approves it (the principal for anything above 50,000 rupees) provided the budget has room;
 * the finance officer then records the vendor payment. Money is whole paise throughout.
 */
@RestController
@RequestMapping("/api/v1")
public class ProcurementController {

    /** Requests above this need PROCUREMENT_APPROVE_LARGE (the principal): 50,000 rupees. */
    static final long LARGE_PAISE = 5_000_000L;

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;

    public ProcurementController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    public record VendorCreate(@NotBlank @Size(min = 2, max = 120) String name, @Size(max = 200) String contact) { }

    public record VendorView(UUID id, String name, String contact) { }

    public record BudgetSet(@NotBlank @Size(min = 2, max = 60) String category, @Min(1) long amountPaise) { }

    public record BudgetView(UUID id, String category, long amountPaise, long committedPaise, long paidPaise) { }

    public record RequestCreate(@NotNull UUID budgetId, UUID vendorId, @NotBlank @Size(min = 3, max = 200) String title,
                                @Size(max = 2000) String description, @Min(1) long amountPaise) { }

    public record Decision(@NotNull @Pattern(regexp = "APPROVE|REJECT|CANCEL") String decision, @Size(max = 2000) String note) { }

    public record Payment(@Min(1) long amountPaise, @NotNull LocalDate paidOn, @NotBlank @Size(min = 2, max = 120) String reference) { }

    public record EventView(String from, String to, String note, String by, OffsetDateTime at) { }

    public record RequestView(UUID id, UUID budgetId, String category, String vendor, String title, String description,
                              long amountPaise, String status, String requestedBy, OffsetDateTime requestedAt, boolean needsPrincipal,
                              Long paidPaise, String paymentReference, List<EventView> history) { }

    private record Req(UUID id, UUID campusId, UUID yearId, UUID budgetId, long amount, UUID requestedBy, String status) { }

    // ---- vendors and budgets ---------------------------------------------------------------------------------

    @PostMapping("/campuses/{campusId}/vendors")
    @ResponseStatus(HttpStatus.CREATED)
    public VendorView addVendor(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody VendorCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            authz.requireOrNotFound(jdbc, ctx, Permission.BUDGET_MANAGE, Resource.ordinary("vendor", null, access.campusPath(jdbc, ctx, campusId), null));
            try {
                UUID id = jdbc.queryForObject("insert into vendor (tenant_id, campus_id, name, contact) values (?, ?, ?, ?) returning id",
                    UUID.class, ctx.tenant().value(), campusId, req.name().trim(), blank(req.contact()));
                audit.write(jdbc, ctx, "procurement.vendor_added", "vendor", id.toString(), "SUCCESS", null, Map.of());
                return new VendorView(id, req.name().trim(), blank(req.contact()));
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "duplicate_vendor", "This campus already has a vendor with that name.");
            }
        });
    }

    @GetMapping("/campuses/{campusId}/vendors")
    public List<VendorView> vendors(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = access.campusPath(jdbc, ctx, campusId);
            if (!authz.check(jdbc, ctx, Permission.PROCUREMENT_READ, Resource.ordinary("vendor", null, path, null)).allowed()) {
                authz.requireOrNotFound(jdbc, ctx, Permission.PROCUREMENT_REQUEST, Resource.ordinary("vendor", null, path, null));
            }
            return jdbc.query("select id, name, contact from vendor where campus_id = ? and active order by name",
                (rs, i) -> new VendorView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), campusId);
        });
    }

    @PutMapping("/academic-years/{yearId}/budgets")
    public BudgetView setBudget(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody BudgetSet req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            authz.requireOrNotFound(jdbc, ctx, Permission.BUDGET_MANAGE, Resource.ordinary("budget", null, access.campusPath(jdbc, ctx, year.campusId()), null));
            UUID id = jdbc.queryForObject("""
                insert into budget (tenant_id, campus_id, academic_year_id, category, amount_paise) values (?, ?, ?, ?, ?)
                on conflict (tenant_id, campus_id, academic_year_id, category) do update set amount_paise = excluded.amount_paise, updated_at = now()
                returning id
                """, UUID.class, ctx.tenant().value(), year.campusId(), yearId, req.category().trim(), req.amountPaise());
            long committed = committed(jdbc, id);
            if (req.amountPaise() < committed) {
                throw new ApiException(HttpStatus.CONFLICT, "below_committed", "The budget cannot be lower than what is already approved.",
                    Map.<String, Object>of("committedPaise", committed));
            }
            audit.write(jdbc, ctx, "procurement.budget_set", "budget", id.toString(), "SUCCESS", null, Map.of("amountPaise", req.amountPaise()));
            return budget(jdbc, id);
        });
    }

    @GetMapping("/academic-years/{yearId}/budgets")
    public List<BudgetView> budgets(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            ScopePath path = access.campusPath(jdbc, ctx, year.campusId());
            Resource scope = Resource.ordinary("budget", null, path, null);
            if (!authz.check(jdbc, ctx, Permission.PROCUREMENT_READ, scope).allowed()) {
                // Requesters need the list of budgets to choose from.
                authz.requireOrNotFound(jdbc, ctx, Permission.PROCUREMENT_REQUEST, scope);
            }
            return jdbc.query(BUDGET_SQL + " where b.academic_year_id = ? group by b.id order by b.category", (rs, i) -> budgetRow(rs), yearId);
        });
    }

    // ---- purchase requests -----------------------------------------------------------------------------------

    @PostMapping("/academic-years/{yearId}/purchase-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public RequestView raise(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody RequestCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            authz.requireOrNotFound(jdbc, ctx, Permission.PROCUREMENT_REQUEST, Resource.ordinary("purchase_request", null, access.campusPath(jdbc, ctx, year.campusId()), null));
            if (jdbc.queryForObject("select count(*) from budget where id = ? and academic_year_id = ?", Long.class, req.budgetId(), yearId) == 0) {
                throw ApiException.badRequest("unknown_budget", "Choose a budget for this campus and year.");
            }
            if (req.vendorId() != null && jdbc.queryForObject("select count(*) from vendor where id = ? and campus_id = ? and active", Long.class,
                req.vendorId(), year.campusId()) == 0) {
                throw ApiException.badRequest("unknown_vendor", "Choose a vendor of this campus.");
            }
            UUID id = jdbc.queryForObject("""
                insert into purchase_request (tenant_id, campus_id, academic_year_id, budget_id, vendor_id, title, description, amount_paise, requested_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), year.campusId(), yearId, req.budgetId(), req.vendorId(), req.title().trim(),
                blank(req.description()), req.amountPaise(), ctx.actor().personId());
            jdbc.update("insert into purchase_event (tenant_id, request_id, to_status, actor_person_id) values (?, ?, 'SUBMITTED', ?)",
                ctx.tenant().value(), id, ctx.actor().personId());
            audit.write(jdbc, ctx, "procurement.requested", "purchase_request", id.toString(), "SUCCESS", null, Map.of("amountPaise", req.amountPaise()));
            return view(jdbc, id);
        });
    }

    /** Approvers and finance see every request; anyone else who can raise requests sees only their own. */
    @GetMapping("/academic-years/{yearId}/purchase-requests")
    public List<RequestView> list(RequestContext ctx, @PathVariable UUID yearId, @RequestParam(required = false) String status) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            Resource scope = Resource.ordinary("purchase_request", null, access.campusPath(jdbc, ctx, year.campusId()), null);
            boolean all = authz.check(jdbc, ctx, Permission.PROCUREMENT_READ, scope).allowed();
            if (!all) {
                authz.requireOrNotFound(jdbc, ctx, Permission.PROCUREMENT_REQUEST, scope);
            }
            List<UUID> ids = jdbc.query("""
                select id from purchase_request where academic_year_id = ? and (? or requested_by = ?) and (?::text is null or status = ?)
                order by (status = 'SUBMITTED') desc, requested_at desc limit 200
                """, (rs, i) -> rs.getObject(1, UUID.class), yearId, all, ctx.actor().personId(), status, status);
            return ids.stream().map(id -> view(jdbc, id)).toList();
        });
    }

    @PostMapping("/purchase-requests/{id}/decision")
    public RequestView decide(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody Decision req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Req r = load(jdbc, id);
            Resource res = Resource.ordinary("purchase_request", id, access.campusPath(jdbc, ctx, r.campusId()), null);
            boolean mine = r.requestedBy().equals(ctx.actor().personId());
            String note = blank(req.note());
            switch (req.decision()) {
                case "CANCEL" -> {
                    if (!mine) {
                        throw ApiException.notFound();
                    }
                }
                default -> {
                    authz.requireOrNotFound(jdbc, ctx, Permission.PROCUREMENT_APPROVE, res);
                    if (mine) {
                        throw ApiException.forbidden("Someone else must approve or reject your request.");
                    }
                    if (r.amount() > LARGE_PAISE) {
                        authz.requireOrForbidden(jdbc, ctx, Permission.PROCUREMENT_APPROVE_LARGE, res);
                    }
                }
            }
            if ("REJECT".equals(req.decision()) && (note == null || note.length() < 5)) {
                throw ApiException.badRequest("note_required", "Say why the request is rejected (5 characters or more).");
            }
            String to = switch (req.decision()) { case "APPROVE" -> "APPROVED"; case "REJECT" -> "REJECTED"; default -> "CANCELLED"; };
            if (!"SUBMITTED".equals(r.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_pending", "This request is already " + r.status().toLowerCase() + ".");
            }
            try {
                jdbc.update("update purchase_request set status = ?, decided_by = ? where id = ?", to, ctx.actor().personId(), id);
            } catch (DataIntegrityViolationException e) {
                String m = String.valueOf(e.getMostSpecificCause().getMessage());
                if (m.contains("over budget")) {
                    throw new ApiException(HttpStatus.CONFLICT, "over_budget", "The budget does not have room for this request.");
                }
                throw new ApiException(HttpStatus.CONFLICT, "invalid_move", "This request cannot move to " + to.toLowerCase() + ".");
            }
            jdbc.update("insert into purchase_event (tenant_id, request_id, from_status, to_status, note, actor_person_id) values (?, ?, ?, ?, ?, ?)",
                ctx.tenant().value(), id, r.status(), to, note, ctx.actor().personId());
            audit.write(jdbc, ctx, "procurement." + to.toLowerCase(), "purchase_request", id.toString(), "SUCCESS", null, Map.of());
            return view(jdbc, id);
        });
    }

    @PostMapping("/purchase-requests/{id}/payment")
    public RequestView pay(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody Payment req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Req r = load(jdbc, id);
            authz.requireOrNotFound(jdbc, ctx, Permission.PAYMENT_RECORD, Resource.ordinary("purchase_request", id, access.campusPath(jdbc, ctx, r.campusId()), null));
            if (!"APPROVED".equals(r.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_payable", "Only an approved request can be paid.");
            }
            if (req.amountPaise() > r.amount()) {
                throw ApiException.badRequest("too_much", "The payment cannot exceed the approved amount.");
            }
            try {
                jdbc.update("insert into vendor_payment (tenant_id, request_id, amount_paise, paid_on, reference, paid_by) values (?, ?, ?, ?, ?, ?)",
                    ctx.tenant().value(), id, req.amountPaise(), req.paidOn(), req.reference().trim(), ctx.actor().personId());
                jdbc.update("update purchase_request set status = 'PAID' where id = ?", id);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "not_payable", "This request cannot be paid.");
            }
            jdbc.update("insert into purchase_event (tenant_id, request_id, from_status, to_status, note, actor_person_id) values (?, ?, 'APPROVED', 'PAID', ?, ?)",
                ctx.tenant().value(), id, "Ref " + req.reference().trim(), ctx.actor().personId());
            audit.write(jdbc, ctx, "procurement.paid", "purchase_request", id.toString(), "SUCCESS", null, Map.of("amountPaise", req.amountPaise()));
            return view(jdbc, id);
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private Req load(JdbcTemplate jdbc, UUID id) {
        return jdbc.query("select id, campus_id, academic_year_id, budget_id, amount_paise, requested_by, status from purchase_request where id = ?",
            (rs, i) -> new Req(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getLong(5), rs.getObject(6, UUID.class), rs.getString(7)), id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private static long committed(JdbcTemplate jdbc, UUID budgetId) {
        return jdbc.queryForObject("select coalesce(sum(amount_paise), 0) from purchase_request where budget_id = ? and status in ('APPROVED','PAID')",
            Long.class, budgetId);
    }

    private static final String BUDGET_SQL = """
        select b.id, b.category, b.amount_paise,
               coalesce(sum(r.amount_paise) filter (where r.status in ('APPROVED','PAID')), 0) as committed,
               coalesce(sum(p.amount_paise), 0) as paid
          from budget b
          left join purchase_request r on r.tenant_id = b.tenant_id and r.budget_id = b.id
          left join vendor_payment p on p.tenant_id = r.tenant_id and p.request_id = r.id
        """;

    private static BudgetView budgetRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BudgetView(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5));
    }

    private BudgetView budget(JdbcTemplate jdbc, UUID id) {
        return jdbc.query(BUDGET_SQL + " where b.id = ? group by b.id", (rs, i) -> budgetRow(rs), id).get(0);
    }

    private RequestView view(JdbcTemplate jdbc, UUID id) {
        List<EventView> history = jdbc.query("""
            select e.from_status, e.to_status, e.note, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), e.at
              from purchase_event e left join person p on p.tenant_id = e.tenant_id and p.id = e.actor_person_id
             where e.request_id = ? order by e.at, e.id
            """, (rs, i) -> new EventView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class)), id);
        return jdbc.query("""
            select r.id, r.budget_id, b.category, v.name, r.title, r.description, r.amount_paise, r.status,
                   trim(coalesce(q.given_name, '') || ' ' || coalesce(q.family_name, '')), r.requested_at, pay.amount_paise, pay.reference
              from purchase_request r
              join budget b on b.tenant_id = r.tenant_id and b.id = r.budget_id
              left join vendor v on v.tenant_id = r.tenant_id and v.id = r.vendor_id
              left join person q on q.tenant_id = r.tenant_id and q.id = r.requested_by
              left join vendor_payment pay on pay.tenant_id = r.tenant_id and pay.request_id = r.id
             where r.id = ?
            """, (rs, i) -> new RequestView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getLong(7), rs.getString(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class),
                rs.getLong(7) > LARGE_PAISE, (Long) rs.getObject(11), rs.getString(12), history), id).get(0);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
