package com.schoomano.api;

import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
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
 * B14 student support (M10). Confidential cases about a child. Only roles holding the student-support permissions
 * (principal and administrators) can see or change them; guardians, teachers and everyone outside the child's scope get
 * "not found". Opening a case's detail is itself written to the audit trail. Notes are permanent and a closed case is final.
 */
@RestController
@RequestMapping("/api/v1")
public class StudentSupportController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ScopeDirectory scopes;

    public StudentSupportController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ScopeDirectory scopes) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.scopes = scopes;
    }

    public record CaseCreate(@NotNull @Pattern(regexp = "^(LEARNING|BEHAVIOUR|WELLBEING|ATTENDANCE|FAMILY|OTHER)$") String category,
                             @NotBlank @Size(min = 2, max = 120) String title, @Size(max = 4000) String firstNote,
                             LocalDate reviewOn) { }

    public record CaseSummary(UUID id, UUID studentId, String studentName, String category, String title, String status,
                              LocalDate reviewOn, boolean reviewDue, LocalDate openedOn) { }

    public record NoteView(UUID id, String body, String author, OffsetDateTime at) { }

    public record CaseDetail(CaseSummary summary, String closedReason, List<NoteView> notes) { }

    public record NoteCreate(@NotBlank @Size(min = 2, max = 4000) String body) { }

    public record StatusChange(@NotNull @Pattern(regexp = "^(OPEN|MONITORING|CLOSED)$") String status,
                               @Size(max = 500) String reason, LocalDate reviewOn) { }

    @PostMapping("/students/{studentId}/support-cases")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseSummary open(RequestContext ctx, @PathVariable UUID studentId, @Valid @RequestBody CaseCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireStudent(jdbc, ctx, studentId, true);
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into student_support_case (tenant_id, student_person_id, category, title, review_on, opened_by)
                    values (?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), studentId, req.category(), req.title().trim(), req.reviewOn(), ctx.actor().personId());
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("invalid_case", "A support case needs an enrolled student.");
            }
            if (req.firstNote() != null && req.firstNote().trim().length() >= 2) {
                addNote(jdbc, ctx, id, req.firstNote().trim());
            }
            audit.write(jdbc, ctx, "support_case.opened", "student_support_case", id.toString(), "SUCCESS", null,
                Map.of("studentId", studentId.toString(), "category", req.category()));
            return summary(jdbc, id);
        });
    }

    @GetMapping("/students/{studentId}/support-cases")
    public List<CaseSummary> forStudent(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireStudent(jdbc, ctx, studentId, false);
            audit.write(jdbc, ctx, "support_case.listed", "student", studentId.toString(), "SUCCESS", null, Map.of());
            return jdbc.query(SUMMARY_SQL + " where c.student_person_id = ? order by c.status = 'CLOSED', c.opened_on desc",
                (rs, i) -> map(rs), studentId);
        });
    }

    /** Cases across a year's students, for the principal's overview: open and monitored first, review dates soonest first. */
    @GetMapping("/academic-years/{yearId}/support-cases")
    public List<CaseSummary> overview(RequestContext ctx, @PathVariable UUID yearId, @RequestParam(required = false) String status) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            ScopePath campus = access.campusPath(jdbc, ctx, year.campusId());
            Resource r = Resource.ordinary("support_case", null, campus, null);
            authz.requireOrNotFound(jdbc, ctx, Permission.STUDENT_SUPPORT_READ, r);
            audit.write(jdbc, ctx, "support_case.listed", "academic_year", yearId.toString(), "SUCCESS", null, Map.of());
            return jdbc.query(SUMMARY_SQL + """
                 where exists (select 1 from student_enrollment e where e.student_person_id = c.student_person_id and e.academic_year_id = ?)
                   and (?::text is null or c.status = ?)
                 order by c.status = 'CLOSED', c.review_on nulls last, c.opened_on desc
                """, (rs, i) -> map(rs), yearId, status, status);
        });
    }

    @GetMapping("/support-cases/{id}")
    public CaseDetail detail(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            CaseSummary s = summary(jdbc, id);
            requireStudent(jdbc, ctx, s.studentId(), false);
            audit.write(jdbc, ctx, "support_case.viewed", "student_support_case", id.toString(), "SUCCESS", null,
                Map.of("studentId", s.studentId().toString()));
            String reason = jdbc.queryForObject("select closed_reason from student_support_case where id = ?", String.class, id);
            List<NoteView> notes = jdbc.query("""
                select n.id, n.body, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), n.created_at
                  from support_case_note n left join person p on p.id = n.author_person_id
                 where n.case_id = ? order by n.created_at, n.id
                """, (rs, i) -> new NoteView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class)), id);
            return new CaseDetail(s, reason, notes);
        });
    }

    @PostMapping("/support-cases/{id}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public NoteView note(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody NoteCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            CaseSummary s = summary(jdbc, id);
            requireStudent(jdbc, ctx, s.studentId(), true);
            if ("CLOSED".equals(s.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "case_closed", "This case is closed and takes no more notes.");
            }
            UUID noteId = addNote(jdbc, ctx, id, req.body().trim());
            audit.write(jdbc, ctx, "support_case.note_added", "student_support_case", id.toString(), "SUCCESS", null, Map.of());
            return jdbc.query("""
                select n.id, n.body, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')), n.created_at
                  from support_case_note n left join person p on p.id = n.author_person_id where n.id = ?
                """, (rs, i) -> new NoteView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime.class)), noteId).get(0);
        });
    }

    @PostMapping("/support-cases/{id}/status")
    public CaseSummary status(RequestContext ctx, @PathVariable UUID id, @Valid @RequestBody StatusChange req) {
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        if ("CLOSED".equals(req.status()) && (reason == null || reason.length() < 5)) {
            throw ApiException.badRequest("reason_required", "Say why the case is being closed (at least 5 characters).");
        }
        return tenantTx.run(ctx.tenant(), jdbc -> {
            CaseSummary s = summary(jdbc, id);
            requireStudent(jdbc, ctx, s.studentId(), true);
            if ("CLOSED".equals(s.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "case_closed", "A closed case stays closed.");
            }
            boolean closing = "CLOSED".equals(req.status());
            jdbc.update("""
                update student_support_case set status = ?, review_on = ?, closed_reason = ?, closed_at = case when ? then now() end where id = ?
                """, req.status(), closing ? null : req.reviewOn() != null ? req.reviewOn() : s.reviewOn(), closing ? reason : null, closing, id);
            audit.write(jdbc, ctx, "support_case.status_changed", "student_support_case", id.toString(), "SUCCESS", reason,
                Map.of("from", s.status(), "to", req.status()));
            return summary(jdbc, id);
        });
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static final String SUMMARY_SQL = """
        select c.id, c.student_person_id, trim(p.given_name || ' ' || coalesce(p.family_name, '')), c.category, c.title, c.status,
               c.review_on, (c.status <> 'CLOSED' and c.review_on is not null and c.review_on <= current_date), c.opened_on
          from student_support_case c join person p on p.id = c.student_person_id
        """;

    private static CaseSummary map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CaseSummary(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getBoolean(8), rs.getObject(9, LocalDate.class));
    }

    private CaseSummary summary(JdbcTemplate jdbc, UUID id) {
        return jdbc.query(SUMMARY_SQL + " where c.id = ?", (rs, i) -> map(rs), id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private UUID addNote(JdbcTemplate jdbc, RequestContext ctx, UUID caseId, String body) {
        return jdbc.queryForObject("""
            insert into support_case_note (tenant_id, case_id, body, author_person_id) values (?, ?, ?, ?) returning id
            """, UUID.class, ctx.tenant().value(), caseId, body, ctx.actor().personId());
    }

    private void requireStudent(JdbcTemplate jdbc, RequestContext ctx, UUID studentId, boolean write) {
        ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
        Resource r = Resource.ordinary("support_case", null, path, studentId);
        authz.requireOrNotFound(jdbc, ctx, Permission.STUDENT_SUPPORT_READ, r);
        if (write) {
            authz.requireOrForbidden(jdbc, ctx, Permission.STUDENT_SUPPORT_WRITE, r);
        }
    }
}
