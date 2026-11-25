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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * M12 student health. The full record and the infirmary log sit in the restricted health partition: only the school
 * nurse (or an assigned case-team member or break-glass holder) can read or write them, everyone else gets "not
 * found". Teachers and other operational staff see just the short emergency flag. Every read of a record is audited.
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ScopeDirectory scopes;

    public HealthController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ScopeDirectory scopes) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.scopes = scopes;
    }

    public record RecordUpdate(@Pattern(regexp = "^(A\\+|A-|B\\+|B-|AB\\+|AB-|O\\+|O-)$") String bloodGroup,
                               @Size(max = 2000) String allergies, @Size(max = 2000) String conditions,
                               @Size(max = 2000) String medicines, @Size(min = 2, max = 80) String emergencyFlag) { }

    public record VisitCreate(@NotBlank @Size(min = 2, max = 1000) String complaint, @Size(max = 1000) String actionTaken,
                              @NotNull @Pattern(regexp = "^(RETURNED_TO_CLASS|SENT_HOME|REFERRED|OTHER)$") String outcome,
                              boolean guardianInformed) { }

    public record VisitView(UUID id, OffsetDateTime visitedAt, String complaint, String actionTaken, String outcome,
                            boolean guardianInformed, String recordedBy) { }

    public record HealthView(UUID studentId, String studentName, String bloodGroup, String allergies, String conditions,
                             String medicines, String emergencyFlag, OffsetDateTime updatedAt, String updatedBy,
                             List<VisitView> visits) { }

    public record Flag(UUID studentId, String studentName, String flag) { }

    @PutMapping("/students/{studentId}/health-record")
    public HealthView save(RequestContext ctx, @PathVariable UUID studentId, @Valid @RequestBody RecordUpdate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireRestricted(jdbc, ctx, studentId);
            try {
                jdbc.update("""
                    insert into health_record (tenant_id, student_person_id, blood_group, allergies, conditions, medicines, emergency_flag, updated_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (tenant_id, student_person_id) do update
                       set blood_group = excluded.blood_group, allergies = excluded.allergies, conditions = excluded.conditions,
                           medicines = excluded.medicines, emergency_flag = excluded.emergency_flag, updated_by = excluded.updated_by
                    """, ctx.tenant().value(), studentId, blankToNull(req.bloodGroup()), blankToNull(req.allergies()),
                    blankToNull(req.conditions()), blankToNull(req.medicines()), blankToNull(req.emergencyFlag()), ctx.actor().personId());
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("invalid_record", "Health information is kept for enrolled students, and the flag needs 2 to 80 characters.");
            }
            audit.write(jdbc, ctx, "health.record_saved", "student", studentId.toString(), "SUCCESS", null, Map.of());
            return view(jdbc, studentId);
        });
    }

    @GetMapping("/students/{studentId}/health-record")
    public HealthView read(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireRestricted(jdbc, ctx, studentId);
            audit.write(jdbc, ctx, "health.record_viewed", "student", studentId.toString(), "SUCCESS", null, Map.of());
            return view(jdbc, studentId);
        });
    }

    @PostMapping("/students/{studentId}/health-visits")
    @ResponseStatus(HttpStatus.CREATED)
    public VisitView visit(RequestContext ctx, @PathVariable UUID studentId, @Valid @RequestBody VisitCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireRestricted(jdbc, ctx, studentId);
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into health_visit (tenant_id, student_person_id, complaint, action_taken, outcome, guardian_informed, recorded_by)
                    values (?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), studentId, req.complaint().trim(), blankToNull(req.actionTaken()),
                    req.outcome(), req.guardianInformed(), ctx.actor().personId());
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("invalid_visit", "A visit needs an enrolled student and a short description.");
            }
            audit.write(jdbc, ctx, "health.visit_recorded", "health_visit", id.toString(), "SUCCESS", null,
                Map.of("studentId", studentId.toString(), "outcome", req.outcome()));
            return jdbc.query(VISIT_SQL + " where v.id = ?", (rs, i) -> visitRow(rs), id).get(0);
        });
    }

    /** The only health information teachers and other operational staff may see: one short emergency line. */
    @GetMapping("/students/{studentId}/health-flag")
    public Flag flag(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.HEALTH_OPERATIONAL_FLAG_READ, Resource.ordinary("health_flag", studentId, path, studentId));
            return jdbc.query("""
                select p.id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')) as name, h.emergency_flag
                  from person p left join health_record h on h.tenant_id = p.tenant_id and h.student_person_id = p.id where p.id = ?
                """, (rs, i) -> new Flag(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("emergency_flag")), studentId).get(0);
        });
    }

    /** Children with an emergency flag across a year, for staff on the campus. */
    @GetMapping("/academic-years/{yearId}/health-flags")
    public List<Flag> flags(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            ScopePath campus = access.campusPath(jdbc, ctx, year.campusId());
            authz.requireOrNotFound(jdbc, ctx, Permission.HEALTH_OPERATIONAL_FLAG_READ, Resource.ordinary("health_flag", null, campus, null));
            audit.write(jdbc, ctx, "health.flags_listed", "academic_year", yearId.toString(), "SUCCESS", null, Map.of());
            return jdbc.query("""
                select p.id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')) as name, h.emergency_flag
                  from health_record h join person p on p.tenant_id = h.tenant_id and p.id = h.student_person_id
                 where h.emergency_flag is not null
                   and exists (select 1 from student_enrollment e where e.student_person_id = h.student_person_id and e.academic_year_id = ?)
                 order by name
                """, (rs, i) -> new Flag(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("emergency_flag")), yearId);
        });
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private void requireRestricted(JdbcTemplate jdbc, RequestContext ctx, UUID studentId) {
        ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
        authz.requireOrNotFound(jdbc, ctx, Permission.HEALTH_RECORD_READ,
            new Resource("health_record", studentId, path, Sensitivity.HEALTH, studentId, Set.of()));
    }

    private static final String VISIT_SQL = """
        select v.id, v.visited_at, v.complaint, v.action_taken, v.outcome, v.guardian_informed,
               trim(coalesce(r.given_name, '') || ' ' || coalesce(r.family_name, '')) as recorder
          from health_visit v left join person r on r.tenant_id = v.tenant_id and r.id = v.recorded_by
        """;

    private static VisitView visitRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VisitView(rs.getObject("id", UUID.class), rs.getObject("visited_at", OffsetDateTime.class), rs.getString("complaint"),
            rs.getString("action_taken"), rs.getString("outcome"), rs.getBoolean("guardian_informed"), rs.getString("recorder"));
    }

    private HealthView view(JdbcTemplate jdbc, UUID studentId) {
        List<VisitView> visits = jdbc.query(VISIT_SQL + " where v.student_person_id = ? order by v.visited_at desc limit 50",
            (rs, i) -> visitRow(rs), studentId);
        return jdbc.query("""
            select p.id, trim(coalesce(p.given_name, '') || ' ' || coalesce(p.family_name, '')) as name,
                   h.blood_group, h.allergies, h.conditions, h.medicines, h.emergency_flag, h.updated_at,
                   trim(coalesce(u.given_name, '') || ' ' || coalesce(u.family_name, '')) as updater
              from person p
              left join health_record h on h.tenant_id = p.tenant_id and h.student_person_id = p.id
              left join person u on u.tenant_id = h.tenant_id and u.id = h.updated_by
             where p.id = ?
            """, (rs, i) -> new HealthView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("blood_group"),
                rs.getString("allergies"), rs.getString("conditions"), rs.getString("medicines"), rs.getString("emergency_flag"),
                rs.getObject("updated_at", OffsetDateTime.class), rs.getString("updater"), visits), studentId).get(0);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
