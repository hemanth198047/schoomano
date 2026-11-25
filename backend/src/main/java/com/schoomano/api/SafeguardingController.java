package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.access.Sensitivity;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import com.schoomano.platform.TenantTx;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
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
 * B15 safeguarding (M11). Any member of staff can raise a concern about a child and gets back only a receipt: raising
 * does not let them read it afterwards. Reading, triaging and acting needs the restricted safeguarding permission, which
 * the access policy gives to the designated safeguarding lead of the campus (not to the principal or group admin by
 * default). Handling follows fixed steps, overdue triage is flagged, and every read is audited.
 */
@RestController
@RequestMapping("/api/v1")
public class SafeguardingController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ScopeDirectory scopes;

    public SafeguardingController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ScopeDirectory scopes) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.scopes = scopes;
    }

    public record ReportCreate(@NotNull @Pattern(regexp = "^(PHYSICAL|EMOTIONAL|NEGLECT|ONLINE|BULLYING|OTHER)$") String category,
                               @NotNull @Pattern(regexp = "^(LOW|MEDIUM|HIGH|IMMEDIATE)$") String severity,
                               @NotBlank @Size(min = 10, max = 4000) String description, LocalDate occurredOn) { }

    public record Receipt(UUID id, OffsetDateTime receivedAt, String message) { }

    public record ReportSummary(UUID id, UUID studentId, String studentName, String category, String severity, String status,
                                OffsetDateTime reportedAt, OffsetDateTime triageDueAt, boolean triageOverdue) { }

    public record ActionView(String from, String to, String note, String by, OffsetDateTime at) { }

    public record ReportDetail(ReportSummary summary, String description, LocalDate occurredOn, String reportedBy, List<ActionView> actions) { }

    public record ActionRequest(@Pattern(regexp = "^(TRIAGED|UNDER_REVIEW|ESCALATED|CLOSED)$") String toStatus,
                                @Size(max = 4000) String note) { }

    @PostMapping("/students/{studentId}/safeguarding-reports")
    @ResponseStatus(HttpStatus.CREATED)
    public Receipt raise(RequestContext ctx, @PathVariable UUID studentId, @Valid @RequestBody ReportCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.SAFEGUARDING_REPORT, Resource.ordinary("safeguarding_report", null, path, studentId));
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into safeguarding_report (tenant_id, student_person_id, category, severity, description, occurred_on, reported_by)
                    values (?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), studentId, req.category(), req.severity(), req.description().trim(),
                    req.occurredOn(), ctx.actor().personId());
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("invalid_report", "A report needs an enrolled student and a date that is not in the future.");
            }
            audit.write(jdbc, ctx, "safeguarding.reported", "safeguarding_report", id.toString(), "SUCCESS", null,
                Map.of("severity", req.severity(), "category", req.category()));
            OffsetDateTime at = jdbc.queryForObject("select reported_at from safeguarding_report where id = ?", OffsetDateTime.class, id);
            return new Receipt(id, at, "Your report has been passed to the safeguarding lead. You do not need to do anything else.");
        });
    }

    @GetMapping("/academic-years/{yearId}/safeguarding-reports")
    public List<ReportSummary> list(RequestContext ctx, @PathVariable UUID yearId, @RequestParam(required = false) String status) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            ScopePath campus = access.campusPath(jdbc, ctx, year.campusId());
            authz.requireOrNotFound(jdbc, ctx, Permission.SAFEGUARDING_CASE_READ, restricted(null, campus, null));
            audit.write(jdbc, ctx, "safeguarding.listed", "academic_year", yearId.toString(), "SUCCESS", null, Map.of());
            return jdbc.query(SUMMARY_SQL + """
                 where exists (select 1 from student_enrollment e where e.student_person_id = r.student_person_id and e.academic_year_id = ?)
                   and (?::text is null or r.status = ?)
                 order by r.status = 'CLOSED', case r.severity when 'IMMEDIATE' then 0 when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end, r.reported_at
                """, (rs, i) -> map(rs), yearId, status, status);
        });
    }

    @GetMapping("/safeguarding-reports/{id}")
    public ReportDetail detail(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ReportSummary s = authorised(jdbc, ctx, id);
            audit.write(jdbc, ctx, "safeguarding.viewed", "safeguarding_report", id.toString(), "SUCCESS", null,
                Map.of("studentId", s.studentId().toString()));
            return detailBody(jdbc, id);
        });
    }

    @PostMapping("/safeguarding-reports/{id}/actions")
    public ReportDetail act(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody ActionRequest req) {
        String note = req.note() == null || req.note().isBlank() ? null : req.note().trim();
        if (req.toStatus() == null && note == null) {
            throw ApiException.badRequest("empty_action", "Record a note, a move to the next step, or both.");
        }
        if (("ESCALATED".equals(req.toStatus()) || "CLOSED".equals(req.toStatus())) && (note == null || note.length() < 5)) {
            throw ApiException.badRequest("note_required", "Escalating or closing needs a written note (at least 5 characters).");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ReportSummary s = authorised(jdbc, ctx, id);
            if ("CLOSED".equals(s.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "report_closed", "A closed report takes no more actions.");
            }
            // The action is written before the move: once a report is closed the database refuses further actions.
            jdbc.update("""
                insert into safeguarding_action (tenant_id, report_id, from_status, to_status, note, actor_person_id) values (?, ?, ?, ?, ?, ?)
                """, ctx.tenant().value(), id, s.status(), req.toStatus(), note, ctx.actor().personId());
            if (req.toStatus() != null) {
                try {
                    jdbc.update("update safeguarding_report set status = ? where id = ?", req.toStatus(), id);
                } catch (DataIntegrityViolationException e) {
                    throw new ApiException(HttpStatus.CONFLICT, "invalid_move", "A report cannot move from " + s.status() + " to " + req.toStatus() + ".");
                }
            }
            audit.write(jdbc, ctx, "safeguarding.acted", "safeguarding_report", id.toString(), "SUCCESS", null,
                Map.of("from", s.status(), "to", req.toStatus() == null ? s.status() : req.toStatus()));
            return detailBody(jdbc, id);
        });
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private ReportDetail detailBody(JdbcTemplate jdbc, UUID id) {
        ReportSummary s = jdbc.query(SUMMARY_SQL + " where r.id = ?", (rs, i) -> map(rs), id).get(0);
        Map<String, Object> row = jdbc.queryForMap("""
            select r.description, r.occurred_on, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')) as reporter
              from safeguarding_report r left join person p on p.id = r.reported_by where r.id = ?
            """, id);
        List<ActionView> actions = jdbc.query("""
            select a.from_status, a.to_status, a.note, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), a.at
              from safeguarding_action a left join person p on p.id = a.actor_person_id
             where a.report_id = ? order by a.at, a.id
            """, (rs, i) -> new ActionView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5, OffsetDateTime.class)), id);
        return new ReportDetail(s, (String) row.get("description"), toLocalDate(row.get("occurred_on")), (String) row.get("reporter"), actions);
    }

    private static LocalDate toLocalDate(Object o) {
        return o == null ? null : ((java.sql.Date) o).toLocalDate();
    }

    private static final String SUMMARY_SQL = """
        select r.id, r.student_person_id, trim(p.given_name || ' ' || coalesce(p.family_name, '')), r.category, r.severity, r.status,
               r.reported_at,
               r.reported_at + case r.severity when 'IMMEDIATE' then interval '1 hour' when 'HIGH' then interval '4 hours' else interval '24 hours' end,
               (r.status = 'REPORTED' and now() > r.reported_at +
                   case r.severity when 'IMMEDIATE' then interval '1 hour' when 'HIGH' then interval '4 hours' else interval '24 hours' end)
          from safeguarding_report r join person p on p.id = r.student_person_id
        """;

    private static ReportSummary map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReportSummary(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getObject(8, OffsetDateTime.class), rs.getBoolean(9));
    }

    /** Loads the report only if the caller may read restricted safeguarding records for that child. */
    private ReportSummary authorised(JdbcTemplate jdbc, RequestContext ctx, UUID id) {
        ReportSummary s = jdbc.query(SUMMARY_SQL + " where r.id = ?", (rs, i) -> map(rs), id).stream().findFirst().orElseThrow(ApiException::notFound);
        ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), s.studentId()).orElseThrow(ApiException::notFound);
        authz.requireOrNotFound(jdbc, ctx, Permission.SAFEGUARDING_CASE_READ, restricted(id, path, s.studentId()));
        return s;
    }

    private static Resource restricted(UUID id, ScopePath path, UUID studentId) {
        return new Resource("safeguarding_case", id, path, Sensitivity.SAFEGUARDING, studentId, Set.of());
    }
}
