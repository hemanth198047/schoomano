package com.schoomano.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.fees.InvoiceRules;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.PaymentGateway;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * B8 fees (M14). Staff with FEE_MANAGE issue invoices and record cash, cheque and office UPI payments; guardians with
 * the pay-fees capability see their child's invoices and pay online. Online payments stay PENDING until the provider's
 * signed webhook arrives; the database applies each event once and refuses to take more than an invoice is worth.
 * Amounts are whole paise throughout.
 */
@RestController
@RequestMapping("/api/v1")
public class FeeController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ScopeDirectory scopes;
    private final ObjectMapper json;
    private final ObjectProvider<PaymentGateway> gatewayProvider;

    public FeeController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ScopeDirectory scopes,
                         ObjectMapper json, ObjectProvider<PaymentGateway> gatewayProvider) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.scopes = scopes;
        this.json = json;
        this.gatewayProvider = gatewayProvider;
    }

    // ---- views and requests ---------------------------------------------------------------------------------

    public record FeeHeadView(UUID id, String code, String name, String status) { }

    public record FeeHeadRequest(@NotBlank @Pattern(regexp = "[A-Z0-9-]{2,16}") String code, @NotBlank @Size(min = 2, max = 80) String name) { }

    public record LineRequest(UUID feeHeadId, @NotBlank @Size(min = 2, max = 120) String description, @Min(1) long amountPaise) { }

    public record InvoiceRequest(@NotNull UUID studentId, @NotNull LocalDate dueOn,
                                 @NotNull @Size(min = 1, max = InvoiceRules.MAX_LINES) List<@Valid LineRequest> lines) { }

    public record BulkRequest(@NotNull UUID sectionId, @NotNull LocalDate dueOn, @NotBlank @Size(min = 8, max = 60) String batchKey,
                              @NotNull @Size(min = 1, max = InvoiceRules.MAX_LINES) List<@Valid LineRequest> lines) { }

    public record LineView(String description, long amountPaise) { }

    public record PaymentView(UUID id, long amountPaise, String method, String status, String reference, OffsetDateTime createdAt,
                              OffsetDateTime completedAt) { }

    public record InvoiceSummary(UUID id, String number, String status, UUID studentId, String studentName, LocalDate dueOn,
                                 long totalPaise, long paidPaise, boolean overdue) { }

    public record InvoiceView(InvoiceSummary invoice, List<LineView> lines, List<PaymentView> payments) { }

    public record OfflinePayment(@Min(1) long amountPaise, @NotNull @Pattern(regexp = "CASH|CHEQUE|UPI_OFFICE") String method,
                                 @Size(max = 60) String reference) { }

    public record PayRequest(Long amountPaise) { }

    public record OrderView(UUID paymentId, String orderId, long amountPaise, String provider, boolean sandbox) { }

    public record CancelRequest(@NotBlank @Size(min = 3, max = 200) String reason) { }

    public record SandboxCompletion(@NotBlank String orderId, @NotNull @Pattern(regexp = "SUCCEEDED|FAILED") String outcome) { }

    public record DayTotal(LocalDate date, String method, int payments, long totalPaise) { }

    public record ExceptionItem(String kind, String invoiceNumber, long amountPaise, OffsetDateTime at, String detail) { }

    public record Reconciliation(List<DayTotal> days, List<ExceptionItem> exceptions, long collectedPaise) { }

    // ---- fee heads ------------------------------------------------------------------------------------------

    @GetMapping("/campuses/{campusId}/fee-heads")
    public List<FeeHeadView> heads(RequestContext ctx, @PathVariable UUID campusId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireStaffRead(jdbc, ctx, access.campusPath(jdbc, ctx, campusId));
            return jdbc.query("select id, code, name, status from fee_head where campus_id = ? order by code",
                (rs, i) -> new FeeHeadView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)), campusId);
        });
    }

    @PostMapping("/campuses/{campusId}/fee-heads")
    @ResponseStatus(HttpStatus.CREATED)
    public FeeHeadView addHead(RequestContext ctx, @PathVariable UUID campusId, @Valid @RequestBody FeeHeadRequest req) {
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            requireManage(jdbc, ctx, access.campusPath(jdbc, ctx, campusId));
            UUID id = jdbc.queryForObject("insert into fee_head (tenant_id, campus_id, code, name) values (?, ?, ?, ?) returning id",
                UUID.class, ctx.tenant().value(), campusId, req.code(), req.name().trim());
            audit.write(jdbc, ctx, "fee.head_created", "fee_head", id.toString(), "SUCCESS", null, Map.of("code", req.code()));
            return new FeeHeadView(id, req.code(), req.name().trim(), "ACTIVE");
        }));
    }

    // ---- invoices -------------------------------------------------------------------------------------------

    private static final String SUMMARY_SQL = """
        select i.id, i.number, i.status, i.student_person_id, trim(p.given_name || ' ' || coalesce(p.family_name, '')),
               i.due_on, i.total_paise, i.paid_paise,
               (i.status in ('ISSUED','PARTIALLY_PAID') and i.due_on < (now() at time zone 'Asia/Kolkata')::date)
          from invoice i join person p on p.id = i.student_person_id
        """;

    private static InvoiceSummary summary(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new InvoiceSummary(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
            rs.getString(5), rs.getObject(6, LocalDate.class), rs.getLong(7), rs.getLong(8), rs.getBoolean(9));
    }

    @PostMapping("/academic-years/{yearId}/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceView issue(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody InvoiceRequest req) {
        checkLines(req.lines());
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, access.campusPath(jdbc, ctx, year.campusId()));
            UUID id = createInvoice(jdbc, ctx, year, req.studentId(), req.dueOn(), req.lines(), null);
            audit.write(jdbc, ctx, "fee.invoice_issued", "invoice", id.toString(), "SUCCESS", null,
                Map.of("studentId", req.studentId().toString(), "lines", req.lines().size()));
            return detail(jdbc, id);
        }));
    }

    /** One invoice per enrolled student of a class. The batch key makes a repeated request skip, not duplicate. */
    @PostMapping("/academic-years/{yearId}/invoices/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> issueForSection(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody BulkRequest req) {
        checkLines(req.lines());
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireManage(jdbc, ctx, access.campusPath(jdbc, ctx, year.campusId()));
            List<UUID> students = jdbc.queryForList("""
                select e.student_person_id from student_enrollment e
                 where e.class_section_id = ? and e.academic_year_id = ? and e.status = 'ENROLLED' order by e.student_person_id
                """, UUID.class, req.sectionId(), yearId);
            int created = 0;
            int skipped = 0;
            for (UUID s : students) {
                boolean exists = jdbc.queryForObject("select count(*) from invoice where batch_key = ? and student_person_id = ?",
                    Integer.class, req.batchKey(), s) > 0;
                if (exists) {
                    skipped++;
                } else {
                    createInvoice(jdbc, ctx, year, s, req.dueOn(), req.lines(), req.batchKey());
                    created++;
                }
            }
            audit.write(jdbc, ctx, "fee.invoices_issued_for_class", "class_section", req.sectionId().toString(), "SUCCESS", null,
                Map.of("created", created, "skipped", skipped, "batchKey", req.batchKey()));
            return Map.<String, Object>of("created", created, "skipped", skipped);
        }));
    }

    private UUID createInvoice(JdbcTemplate jdbc, RequestContext ctx, AcademicAccess.Year year, UUID studentId, LocalDate dueOn,
                               List<LineRequest> lines, String batchKey) {
        long total = lines.stream().mapToLong(LineRequest::amountPaise).sum();
        String number = jdbc.queryForObject("select fee_next_invoice_number(?)", String.class, year.id());
        UUID id = jdbc.queryForObject("""
            insert into invoice (tenant_id, campus_id, academic_year_id, student_person_id, number, due_on, total_paise, batch_key, issued_by)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
            """, UUID.class, ctx.tenant().value(), year.campusId(), year.id(), studentId, number, dueOn, total, batchKey,
            ctx.actor().personId());
        for (LineRequest l : lines) {
            jdbc.update("insert into invoice_line (tenant_id, invoice_id, fee_head_id, description, amount_paise) values (?, ?, ?, ?, ?)",
                ctx.tenant().value(), id, l.feeHeadId(), l.description().trim(), l.amountPaise());
        }
        return id;
    }

    private static void checkLines(List<LineRequest> lines) {
        List<String> problems = InvoiceRules.problems(lines.stream().map(LineRequest::amountPaise).toList());
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_invoice", "The invoice needs a few changes.", Map.<String, Object>of("problems", problems));
        }
    }

    @GetMapping("/academic-years/{yearId}/invoices")
    public List<InvoiceSummary> list(RequestContext ctx, @PathVariable UUID yearId, @RequestParam(required = false) String status,
                                     @RequestParam(required = false) UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            requireStaffRead(jdbc, ctx, access.campusPath(jdbc, ctx, year.campusId()));
            return jdbc.query(SUMMARY_SQL + """
                 where i.academic_year_id = ? and (?::text is null or i.status = ?::text) and (?::uuid is null or i.student_person_id = ?::uuid)
                 order by i.number desc limit 500
                """, FeeController::summary, yearId, status, status, studentId, studentId);
        });
    }

    /** A child's invoices: staff with FEE_READ in scope, or a guardian with the pay-fees capability. */
    @GetMapping("/students/{studentId}/invoices")
    public List<InvoiceSummary> forStudent(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.FEE_READ, Resource.ordinary("invoice", null, path, studentId));
            return jdbc.query(SUMMARY_SQL + " where i.student_person_id = ? order by i.issued_at desc", FeeController::summary, studentId);
        });
    }

    @GetMapping("/invoices/{invoiceId}")
    public InvoiceView invoice(RequestContext ctx, @PathVariable UUID invoiceId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Inv inv = load(jdbc, ctx, invoiceId);
            authz.requireOrNotFound(jdbc, ctx, Permission.FEE_READ, inv.resource());
            return detail(jdbc, invoiceId);
        });
    }

    @PostMapping("/invoices/{invoiceId}/cancel")
    public InvoiceView cancel(RequestContext ctx, @PathVariable UUID invoiceId, @Valid @RequestBody CancelRequest req) {
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            Inv inv = load(jdbc, ctx, invoiceId);
            authz.requireOrNotFound(jdbc, ctx, Permission.FEE_READ, inv.resource());
            authz.requireOrForbidden(jdbc, ctx, Permission.FEE_MANAGE, inv.resource());
            jdbc.update("update invoice set status = 'CANCELLED', cancelled_at = now(), cancel_reason = ? where id = ?", req.reason().trim(), invoiceId);
            audit.write(jdbc, ctx, "fee.invoice_cancelled", "invoice", invoiceId.toString(), "SUCCESS", req.reason().trim(), Map.of());
            return detail(jdbc, invoiceId);
        }));
    }

    // ---- payments -------------------------------------------------------------------------------------------

    /** Cash, cheque or office UPI taken at the counter. Repeating the Idempotency-Key returns the first payment. */
    @PostMapping("/invoices/{invoiceId}/payments/offline")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceView recordOffline(RequestContext ctx, @PathVariable UUID invoiceId,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String key, @Valid @RequestBody OfflinePayment req) {
        requireKey(key);
        if ("CHEQUE".equals(req.method()) && (req.reference() == null || req.reference().isBlank())) {
            throw ApiException.badRequest("reference_required", "Enter the cheque number.");
        }
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            Inv inv = load(jdbc, ctx, invoiceId);
            authz.requireOrNotFound(jdbc, ctx, Permission.FEE_READ, inv.resource());
            authz.requireOrForbidden(jdbc, ctx, Permission.FEE_MANAGE, inv.resource());
            var existing = jdbc.query("select invoice_id, amount_paise from payment where idempotency_key = ?",
                (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getLong(2)}, key).stream().findFirst();
            if (existing.isPresent()) {
                if (!existing.get()[0].equals(invoiceId) || (long) existing.get()[1] != req.amountPaise()) {
                    throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused", "That key was used for a different payment. Use a new key.");
                }
                return detail(jdbc, invoiceId);
            }
            String problem = InvoiceRules.paymentProblem(inv.total(), inv.paid(), req.amountPaise());
            if (problem != null || "CANCELLED".equals(inv.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "payment_rejected", problem != null ? problem : "The invoice is cancelled.");
            }
            UUID id = jdbc.queryForObject("""
                insert into payment (tenant_id, invoice_id, amount_paise, method, status, reference, idempotency_key, created_by, completed_at)
                values (?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?, now()) returning id
                """, UUID.class, ctx.tenant().value(), invoiceId, req.amountPaise(), req.method(),
                req.reference() == null ? null : req.reference().trim(), key, ctx.actor().personId());
            audit.write(jdbc, ctx, "fee.payment_recorded", "payment", id.toString(), "SUCCESS", null,
                Map.of("invoice", inv.number(), "amountPaise", req.amountPaise(), "method", req.method()));
            return detail(jdbc, invoiceId);
        }));
    }

    /** A guardian starts an online payment for all or part of what is due. Needs the pay-fees capability. */
    @PostMapping("/invoices/{invoiceId}/pay")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView pay(RequestContext ctx, @PathVariable UUID invoiceId,
                         @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody(required = false) PayRequest req) {
        requireKey(key);
        PaymentGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "payments_unavailable", "Online payment is not set up for this school yet.");
        }
        return guarded(() -> tenantTx.run(ctx.tenant(), jdbc -> {
            Inv inv = load(jdbc, ctx, invoiceId);
            authz.requireOrNotFound(jdbc, ctx, Permission.FEE_READ, inv.resource());
            authz.requireOrForbidden(jdbc, ctx, Permission.FEE_PAY, inv.resource());
            var existing = jdbc.query("select id, invoice_id, amount_paise, gateway_order_id from payment where idempotency_key = ?",
                (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4)}, key)
                .stream().findFirst();
            if (existing.isPresent()) {
                if (!existing.get()[1].equals(invoiceId)) {
                    throw new ApiException(HttpStatus.CONFLICT, "idempotency_key_reused", "That key was used for a different payment. Use a new key.");
                }
                return new OrderView((UUID) existing.get()[0], (String) existing.get()[3], (long) existing.get()[2], gateway.name(), gateway.sandbox());
            }
            long due = inv.total() - inv.paid();
            long amount = req == null || req.amountPaise() == null ? due : req.amountPaise();
            String problem = "CANCELLED".equals(inv.status()) ? "The invoice is cancelled." : InvoiceRules.paymentProblem(inv.total(), inv.paid(), amount);
            if (problem != null) {
                throw new ApiException(HttpStatus.CONFLICT, "payment_rejected", problem);
            }
            String order = gateway.createOrder(inv.number(), amount);
            UUID id = jdbc.queryForObject("""
                insert into payment (tenant_id, invoice_id, amount_paise, method, status, gateway_order_id, idempotency_key, created_by)
                values (?, ?, ?, 'GATEWAY', 'PENDING', ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), invoiceId, amount, order, key, ctx.actor().personId());
            audit.write(jdbc, ctx, "fee.payment_started", "payment", id.toString(), "SUCCESS", null,
                Map.of("invoice", inv.number(), "amountPaise", amount));
            return new OrderView(id, order, amount, gateway.name(), gateway.sandbox());
        }));
    }

    /**
     * Sandbox only: stands in for the provider's checkout page. It builds a signed event for the caller's own pending
     * payment and sends it through exactly the same code as a real webhook.
     */
    @PostMapping("/payments/sandbox/complete")
    public Map<String, Object> sandboxComplete(RequestContext ctx, @Valid @RequestBody SandboxCompletion req) {
        PaymentGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null || !gateway.sandbox()) {
            throw ApiException.notFound();
        }
        record Own(UUID invoiceId, long amount) { }
        Own own = tenantTx.run(ctx.tenant(), jdbc -> {
            var row = jdbc.query("select invoice_id, amount_paise from payment where gateway_order_id = ? and created_by = ?",
                (rs, i) -> new Own(rs.getObject(1, UUID.class), rs.getLong(2)), req.orderId(), ctx.actor().personId()).stream().findFirst()
                .orElseThrow(ApiException::notFound);
            return row;
        });
        String body = write(Map.of("eventId", "sbx-" + UUID.randomUUID(), "orderId", req.orderId(), "paymentId", "pay_" + UUID.randomUUID().toString().substring(0, 12),
            "amountPaise", own.amount(), "status", req.outcome()));
        return Map.of("outcome", applyEvent(gateway.name(), body));
    }

    /** The provider's webhook. The signature over the raw body is the only credential. Always 200 once authentic. */
    @PostMapping("/payments/webhook")
    public Map<String, Object> webhook(@RequestHeader(value = "X-Signature", required = false) String signature, @RequestBody String body) {
        PaymentGateway gateway = gatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "payments_unavailable", "Online payment is not set up.");
        }
        if (!gateway.verify(body, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_signature", "The signature does not match.");
        }
        return Map.of("outcome", applyEvent(gateway.name(), body));
    }

    private String applyEvent(String provider, String body) {
        JsonNode n;
        try {
            n = json.readTree(body);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("invalid_event", "The event could not be read.");
        }
        String event = text(n, "eventId");
        String order = text(n, "orderId");
        String status = text(n, "status");
        if (event == null || order == null || status == null || !n.hasNonNull("amountPaise") || !n.get("amountPaise").canConvertToLong()) {
            throw ApiException.badRequest("invalid_event", "The event is missing required fields.");
        }
        String paymentRef = text(n, "paymentId");
        long amount = n.get("amountPaise").asLong();
        return tenantTx.runWithoutTenant(jdbc -> jdbc.queryForObject("select fee_apply_gateway_event(?, ?, ?, ?, ?, ?)", String.class,
            provider, event, order, paymentRef, amount, status));
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    // ---- reconciliation -------------------------------------------------------------------------------------

    /** What came in per day and method, and what a person must look at: refunds due, mismatches, stale pending payments. */
    @GetMapping("/campuses/{campusId}/fees/reconciliation")
    public Reconciliation reconciliation(RequestContext ctx, @PathVariable UUID campusId, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireStaffRead(jdbc, ctx, access.campusPath(jdbc, ctx, campusId));
            List<DayTotal> days = jdbc.query("""
                select (p.completed_at at time zone 'Asia/Kolkata')::date, p.method, count(*), sum(p.amount_paise)
                  from payment p join invoice i on i.id = p.invoice_id
                 where i.campus_id = ? and p.status = 'SUCCEEDED'
                   and (p.completed_at at time zone 'Asia/Kolkata')::date between ? and ?
                 group by 1, 2 order by 1, 2
                """, (rs, i) -> new DayTotal(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getInt(3), rs.getLong(4)), campusId, from, to);
            List<ExceptionItem> ex = new ArrayList<>();
            ex.addAll(jdbc.query("""
                select i.number, p.amount_paise, p.completed_at, 'Money was taken but the invoice could not accept it. Refund it.'
                  from payment p join invoice i on i.id = p.invoice_id where i.campus_id = ? and p.status = 'NEEDS_REFUND' order by p.completed_at
                """, (rs, i) -> new ExceptionItem("REFUND_DUE", rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4)), campusId));
            ex.addAll(jdbc.query("""
                select i.number, p.amount_paise, e.received_at, 'The provider reported ' || e.amount_paise || ' paise for an order of ' || p.amount_paise || '. Check with the provider.'
                  from gateway_event e join payment p on p.gateway_order_id = e.order_id join invoice i on i.id = p.invoice_id
                 where i.campus_id = ? and e.outcome = 'AMOUNT_MISMATCH' and p.status = 'PENDING' order by e.received_at
                """, (rs, i) -> new ExceptionItem("AMOUNT_MISMATCH", rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4)), campusId));
            ex.addAll(jdbc.query("""
                select i.number, p.amount_paise, p.created_at, 'Started more than a day ago and never confirmed by the provider.'
                  from payment p join invoice i on i.id = p.invoice_id
                 where i.campus_id = ? and p.status = 'PENDING' and p.created_at < now() - interval '1 day' order by p.created_at
                """, (rs, i) -> new ExceptionItem("STALE_PENDING", rs.getString(1), rs.getLong(2), rs.getObject(3, OffsetDateTime.class), rs.getString(4)), campusId));
            return new Reconciliation(days, ex, days.stream().mapToLong(DayTotal::totalPaise).sum());
        });
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private record Inv(UUID id, String number, String status, long total, long paid, UUID studentId, ScopePath path) {
        Resource resource() {
            return Resource.ordinary("invoice", id, path, studentId);
        }
    }

    /** Loads an invoice with its campus path; the caller then asks the policy (staff scope or guardian capability). */
    private Inv load(JdbcTemplate jdbc, RequestContext ctx, UUID invoiceId) {
        Optional<Object[]> row = jdbc.query("select campus_id, number, status, total_paise, paid_paise, student_person_id from invoice where id = ?",
            (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5), rs.getObject(6, UUID.class)},
            invoiceId).stream().findFirst();
        Object[] r = row.orElseThrow(ApiException::notFound);
        return new Inv(invoiceId, (String) r[1], (String) r[2], (long) r[3], (long) r[4], (UUID) r[5], access.campusPath(jdbc, ctx, (UUID) r[0]));
    }

    private InvoiceView detail(JdbcTemplate jdbc, UUID id) {
        InvoiceSummary s = jdbc.queryForObject(SUMMARY_SQL + " where i.id = ?", FeeController::summary, id);
        List<LineView> lines = jdbc.query("select description, amount_paise from invoice_line where invoice_id = ? order by id",
            (rs, i) -> new LineView(rs.getString(1), rs.getLong(2)), id);
        List<PaymentView> payments = jdbc.query("""
            select id, amount_paise, method, status, reference, created_at, completed_at from payment where invoice_id = ? order by created_at
            """, (rs, i) -> new PaymentView(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getObject(6, OffsetDateTime.class), rs.getObject(7, OffsetDateTime.class)), id);
        return new InvoiceView(s, lines, payments);
    }

    private void requireStaffRead(JdbcTemplate jdbc, RequestContext ctx, ScopePath path) {
        authz.requireOrNotFound(jdbc, ctx, Permission.FEE_READ, Resource.ordinary("invoice", null, path, null));
    }

    private void requireManage(JdbcTemplate jdbc, RequestContext ctx, ScopePath path) {
        requireStaffRead(jdbc, ctx, path);
        authz.requireOrForbidden(jdbc, ctx, Permission.FEE_MANAGE, Resource.ordinary("invoice", null, path, null));
    }

    private static void requireKey(String key) {
        if (key == null || key.length() < 8 || key.length() > 100) {
            throw ApiException.badRequest("idempotency_key_required", "Send an Idempotency-Key of 8 to 100 characters.");
        }
    }

    private String write(Map<String, Object> m) {
        try {
            return json.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The database's own refusals become plain messages. */
    private static <T> T guarded(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataIntegrityViolationException e) {
            String raw = e.getMostSpecificCause().getMessage();
            String first = raw == null ? "" : raw.lines().findFirst().orElse("");
            String message = "That change is not allowed.";
            if (first.contains("duplicate key") && first.contains("code")) {
                message = "A fee head with that code already exists.";
            } else if (first.contains("duplicate key")) {
                message = "That already exists.";
            } else if (first.startsWith("ERROR: ") && first.length() > 7) {
                String text = first.substring(7).trim();
                message = Character.toUpperCase(text.charAt(0)) + text.substring(1) + ".";
            }
            throw new ApiException(HttpStatus.CONFLICT, "fee_rejected", message);
        }
    }
}
