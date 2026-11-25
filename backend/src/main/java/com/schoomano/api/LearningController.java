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
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
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
 * B13 curriculum and learning (M07, M08). Administrators keep the syllabus of each grade and subject; teachers mark
 * which units a class has been taught and set homework; guardians with the view-academic capability see their
 * child's homework. Coverage is permanent once recorded, and homework can only be cancelled, never quietly edited.
 */
@RestController
@RequestMapping("/api/v1")
public class LearningController {

    private final TenantTx tenantTx;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;
    private final ScopeDirectory scopes;

    public LearningController(TenantTx tenantTx, Authorizer authz, AuditWriter audit, AcademicAccess access, ScopeDirectory scopes) {
        this.tenantTx = tenantTx;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
        this.scopes = scopes;
    }

    public record UnitCreate(@NotNull UUID subjectId, UUID termId, @NotBlank @Size(min = 2, max = 120) String title,
                             @Size(max = 1000) String outcomes) { }

    public record Unit(UUID id, UUID subjectId, String subjectName, UUID termId, int ordinal, String title, String outcomes) { }

    public record CoverageCreate(@NotNull UUID unitId, LocalDate coveredOn) { }

    public record CoverageUnit(UUID id, int ordinal, String title, String outcomes, LocalDate coveredOn) { }

    public record Coverage(UUID subjectId, int total, int covered, List<CoverageUnit> units) { }

    public record HomeworkCreate(@NotNull UUID subjectId, @NotBlank @Size(min = 2, max = 120) String title,
                                 @Size(max = 2000) String instructions, @NotNull LocalDate dueOn) { }

    public record Homework(UUID id, UUID sectionId, UUID subjectId, String subjectName, String title, String instructions,
                           LocalDate assignedOn, LocalDate dueOn, String status) { }

    // ---- syllabus -------------------------------------------------------------------------------------------

    @GetMapping("/grade-levels/{gradeId}/curriculum")
    public List<Unit> syllabus(RequestContext ctx, @PathVariable UUID gradeId, @RequestParam(required = false) UUID subjectId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireGrade(jdbc, ctx, gradeId, Permission.LEARNING_READ, false);
            return jdbc.query(UNIT_SQL + " where u.grade_level_id = ? and (?::uuid is null or u.subject_id = ?) order by s.name, u.ordinal",
                (rs, i) -> unit(rs), gradeId, subjectId, subjectId);
        });
    }

    @PostMapping("/grade-levels/{gradeId}/curriculum")
    @ResponseStatus(HttpStatus.CREATED)
    public Unit addUnit(RequestContext ctx, @PathVariable UUID gradeId, @Valid @RequestBody UnitCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireGrade(jdbc, ctx, gradeId, Permission.CURRICULUM_MANAGE, true);
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into curriculum_unit (tenant_id, grade_level_id, subject_id, term_id, ordinal, title, outcomes, created_by)
                    values (?, ?, ?, ?, (select coalesce(max(ordinal), 0) + 1 from curriculum_unit where grade_level_id = ? and subject_id = ?),
                            ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), gradeId, req.subjectId(), req.termId(), gradeId, req.subjectId(),
                    req.title().trim(), blank(req.outcomes()), ctx.actor().personId());
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("invalid_unit", "The subject and term must belong to this grade's programme.");
            }
            audit.write(jdbc, ctx, "curriculum.unit_added", "curriculum_unit", id.toString(), "SUCCESS", null,
                Map.of("gradeLevelId", gradeId.toString(), "subjectId", req.subjectId().toString()));
            return jdbc.query(UNIT_SQL + " where u.id = ?", (rs, i) -> unit(rs), id).get(0);
        });
    }

    // ---- coverage -------------------------------------------------------------------------------------------

    @GetMapping("/sections/{sectionId}/coverage")
    public Coverage coverage(RequestContext ctx, @PathVariable UUID sectionId, @RequestParam UUID subjectId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireSection(jdbc, ctx, sectionId, Permission.LEARNING_READ, false);
            List<CoverageUnit> units = jdbc.query("""
                select u.id, u.ordinal, u.title, u.outcomes, c.covered_on
                  from class_section s
                  join curriculum_unit u on u.grade_level_id = s.grade_level_id and u.subject_id = ?
                  left join unit_coverage c on c.unit_id = u.id and c.class_section_id = s.id
                 where s.id = ? order by u.ordinal
                """, (rs, i) -> new CoverageUnit(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
                    rs.getObject(5, LocalDate.class)), subjectId, sectionId);
            return new Coverage(subjectId, units.size(), (int) units.stream().filter(u -> u.coveredOn() != null).count(), units);
        });
    }

    @PostMapping("/sections/{sectionId}/coverage")
    @ResponseStatus(HttpStatus.CREATED)
    public CoverageUnit markCovered(RequestContext ctx, @PathVariable UUID sectionId, @Valid @RequestBody CoverageCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireSection(jdbc, ctx, sectionId, Permission.LEARNING_WRITE, true);
            LocalDate on = req.coveredOn() != null ? req.coveredOn() : jdbc.queryForObject("select current_date", LocalDate.class);
            try {
                jdbc.update("""
                    insert into unit_coverage (tenant_id, class_section_id, unit_id, covered_on, recorded_by) values (?, ?, ?, ?, ?)
                    """, ctx.tenant().value(), sectionId, req.unitId(), on, ctx.actor().personId());
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new ApiException(HttpStatus.CONFLICT, "already_covered", "This class is already marked as having covered that unit.");
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("invalid_coverage", "That unit is not in this class's grade, or the date is in the future.");
            }
            audit.write(jdbc, ctx, "curriculum.unit_covered", "curriculum_unit", req.unitId().toString(), "SUCCESS", null,
                Map.of("sectionId", sectionId.toString(), "coveredOn", on.toString()));
            return jdbc.query("select u.id, u.ordinal, u.title, u.outcomes, c.covered_on from curriculum_unit u join unit_coverage c on c.unit_id = u.id where u.id = ? and c.class_section_id = ?",
                (rs, i) -> new CoverageUnit(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4),
                    rs.getObject(5, LocalDate.class)), req.unitId(), sectionId).get(0);
        });
    }

    // ---- homework -------------------------------------------------------------------------------------------

    @PostMapping("/sections/{sectionId}/homework")
    @ResponseStatus(HttpStatus.CREATED)
    public Homework setHomework(RequestContext ctx, @PathVariable UUID sectionId, @Valid @RequestBody HomeworkCreate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireSection(jdbc, ctx, sectionId, Permission.LEARNING_WRITE, true);
            UUID id;
            try {
                id = jdbc.queryForObject("""
                    insert into homework (tenant_id, class_section_id, subject_id, title, instructions, due_on, created_by)
                    values (?, ?, ?, ?, ?, ?, ?) returning id
                    """, UUID.class, ctx.tenant().value(), sectionId, req.subjectId(), req.title().trim(), blank(req.instructions()),
                    req.dueOn(), ctx.actor().personId());
            } catch (DataIntegrityViolationException e) {
                throw ApiException.badRequest("invalid_homework", "Choose a subject of this class, with a due date that is today or later.");
            }
            audit.write(jdbc, ctx, "homework.set", "homework", id.toString(), "SUCCESS", null,
                Map.of("sectionId", sectionId.toString(), "dueOn", req.dueOn().toString()));
            return homework(jdbc, id);
        });
    }

    @GetMapping("/sections/{sectionId}/homework")
    public List<Homework> sectionHomework(RequestContext ctx, @PathVariable UUID sectionId,
                                          @RequestParam(defaultValue = "false") boolean includePast) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            requireSection(jdbc, ctx, sectionId, Permission.LEARNING_READ, false);
            return jdbc.query(HW_SQL + " where h.class_section_id = ? and (? or h.due_on >= current_date - 7) order by h.due_on desc, h.created_at desc",
                (rs, i) -> hw(rs), sectionId, includePast);
        });
    }

    @PostMapping("/homework/{id}/cancel")
    public Homework cancel(RequestContext ctx, @PathVariable UUID id) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Homework h = homework(jdbc, id);
            requireSection(jdbc, ctx, h.sectionId(), Permission.LEARNING_WRITE, true);
            if (!"CANCELLED".equals(h.status())) {
                jdbc.update("update homework set status = 'CANCELLED' where id = ?", id);
                audit.write(jdbc, ctx, "homework.cancelled", "homework", id.toString(), "SUCCESS", null, Map.of("sectionId", h.sectionId().toString()));
            }
            return homework(jdbc, id);
        });
    }

    /** Active homework for one child: due from last week onwards. Guardians need view-academic. */
    @GetMapping("/students/{studentId}/homework")
    public List<Homework> studentHomework(RequestContext ctx, @PathVariable UUID studentId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath path = scopes.studentPath(jdbc, ctx.tenant(), studentId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.STUDENT_READ_ACADEMIC, Resource.ordinary("homework", null, path, studentId));
            return jdbc.query(HW_SQL + """
                 join student_enrollment e on e.class_section_id = h.class_section_id and e.student_person_id = ? and e.status = 'ENROLLED'
                 where h.status = 'ACTIVE' and h.due_on >= current_date - 7 order by h.due_on, h.created_at
                """, (rs, i) -> hw(rs), studentId);
        });
    }

    // ---- helpers --------------------------------------------------------------------------------------------

    private static final String UNIT_SQL = """
        select u.id, u.subject_id, s.name, u.term_id, u.ordinal, u.title, u.outcomes
          from curriculum_unit u join subject s on s.id = u.subject_id
        """;
    private static final String HW_SQL = """
        select h.id, h.class_section_id, h.subject_id, s.name, h.title, h.instructions, h.assigned_on, h.due_on, h.status
          from homework h join subject s on s.id = h.subject_id
        """;

    private static Unit unit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Unit(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
            rs.getInt(5), rs.getString(6), rs.getString(7));
    }

    private static Homework hw(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Homework(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class), rs.getString(9));
    }

    private Homework homework(JdbcTemplate jdbc, UUID id) {
        return jdbc.query(HW_SQL + " where h.id = ?", (rs, i) -> hw(rs), id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private void requireGrade(JdbcTemplate jdbc, RequestContext ctx, UUID gradeId, Permission permission, boolean write) {
        UUID campusId = jdbc.queryForList("select p.campus_id from grade_level g join programme p on p.id = g.programme_id where g.id = ?",
            UUID.class, gradeId).stream().findFirst().orElseThrow(ApiException::notFound);
        ScopePath path = access.campusPath(jdbc, ctx, campusId);
        Resource r = Resource.ordinary("curriculum", gradeId, path, null);
        authz.requireOrNotFound(jdbc, ctx, Permission.LEARNING_READ, r);
        if (write) {
            authz.requireOrForbidden(jdbc, ctx, permission, r);
        }
    }

    private void requireSection(JdbcTemplate jdbc, RequestContext ctx, UUID sectionId, Permission permission, boolean write) {
        ScopePath path = access.sectionPath(jdbc, ctx, sectionId);
        Resource r = Resource.ordinary("learning", sectionId, path, null);
        authz.requireOrNotFound(jdbc, ctx, Permission.LEARNING_READ, r);
        if (write) {
            authz.requireOrForbidden(jdbc, ctx, permission, r);
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
