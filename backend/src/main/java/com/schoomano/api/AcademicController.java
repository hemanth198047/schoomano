package com.schoomano.api;

import com.schoomano.domain.academic.DateRange;
import com.schoomano.domain.academic.RolloverPlanner;
import com.schoomano.domain.academic.TermRules;
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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.HashMap;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * B1 academic structure (M04): grades and subjects per programme; terms, sections and calendar per
 * academic year; rollover preview. Reads need CAMPUS_READ, writes ACADEMIC_STRUCTURE_MANAGE in scope.
 * Every write is audited in the same transaction. Grade scales are config ('assessment.grade_scale').
 */
@RestController
@RequestMapping("/api/v1")
public class AcademicController {

    private final TenantTx tenantTx;
    private final ScopeDirectory scopes;
    private final Authorizer authz;
    private final AuditWriter audit;
    private final AcademicAccess access;

    public AcademicController(TenantTx tenantTx, ScopeDirectory scopes, Authorizer authz, AuditWriter audit,
                              AcademicAccess access) {
        this.tenantTx = tenantTx;
        this.scopes = scopes;
        this.authz = authz;
        this.audit = audit;
        this.access = access;
    }

    // ---- views ----------------------------------------------------------------------------------

    public record YearView(UUID id, String label, LocalDate startsOn, LocalDate endsOn, String status) { }
    public record SectionView(UUID id, String code, Integer capacity, int students) { }
    public record GradeView(UUID id, String code, String name, int ordinal, List<SectionView> sections) { }
    public record SubjectView(UUID id, String code, String name, String status) { }
    public record TermView(UUID id, String label, LocalDate startsOn, LocalDate endsOn) { }
    public record ProgrammeView(UUID id, String code, String name, String board, String boardDetail,
                                List<GradeView> grades, List<SubjectView> subjects, List<TermView> terms) { }
    public record CalendarEventView(UUID id, String kind, String title, LocalDate startsOn, LocalDate endsOn,
                                    UUID programmeId) { }
    public record StructureView(UUID campusId, String campusName, List<YearView> years, YearView year,
                                List<ProgrammeView> programmes, List<CalendarEventView> calendar) { }

    // ---- requests -------------------------------------------------------------------------------

    public record GradeRequest(@NotBlank @Pattern(regexp = "^[A-Z0-9-]{1,12}$") String code,
                               @NotBlank @Size(max = 80) String name, @NotNull @Min(-3) @Max(20) Integer ordinal) { }
    public record SubjectRequest(@NotBlank @Pattern(regexp = "^[A-Z0-9-]{2,16}$") String code,
                                 @NotBlank @Size(max = 80) String name) { }
    public record TermRequest(@NotNull UUID programmeId, @NotBlank @Size(max = 40) String label,
                              @NotNull LocalDate startsOn, @NotNull LocalDate endsOn) { }
    public record SectionRequest(@NotNull UUID gradeId, @NotBlank @Pattern(regexp = "^[A-Z0-9-]{1,8}$") String code,
                                 @Min(1) @Max(200) Integer capacity) { }
    public record CalendarEventRequest(@NotNull @Pattern(regexp = "^(HOLIDAY|EXAM|EVENT|WORKING_DAY)$") String kind,
                                       @NotBlank @Size(max = 120) String title, @NotNull LocalDate startsOn,
                                       @NotNull LocalDate endsOn, UUID programmeId) { }

    // ---- reads ----------------------------------------------------------------------------------

    @GetMapping("/campuses/{campusId}/academic-structure")
    public StructureView structure(RequestContext ctx, @PathVariable UUID campusId,
                                   @RequestParam(required = false) UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            ScopePath campus = scopes.campusPath(jdbc, ctx.tenant(), campusId).orElseThrow(ApiException::notFound);
            authz.requireOrNotFound(jdbc, ctx, Permission.CAMPUS_READ, Resource.ordinary("campus", campusId, campus, null));
            String campusName = jdbc.queryForObject("select name from campus where id = ?", String.class, campusId);
            List<AcademicAccess.Year> years = jdbc.query("""
                select id, campus_id, label, starts_on, ends_on, status from academic_year
                 where campus_id = ? order by starts_on desc
                """, (rs, i) -> AcademicAccess.year(rs), campusId);
            AcademicAccess.Year selected = yearId != null
                ? years.stream().filter(y -> y.id().equals(yearId)).findFirst().orElseThrow(ApiException::notFound)
                : years.stream().filter(y -> "ACTIVE".equals(y.status())).findFirst()
                    .orElse(years.isEmpty() ? null : years.get(0));
            UUID selId = selected == null ? null : selected.id();

            List<ProgrammeView> programmes = jdbc.query("""
                select id, code, name, board, board_detail from programme where campus_id = ? order by code
                """, (rs, i) -> {
                    UUID pid = rs.getObject(1, UUID.class);
                    return new ProgrammeView(pid, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        grades(jdbc, pid, selId), subjects(jdbc, pid), terms(jdbc, pid, selId));
                }, campusId);

            List<CalendarEventView> calendar = selId == null ? List.of() : jdbc.query("""
                select id, kind, title, starts_on, ends_on, programme_id from calendar_event
                 where academic_year_id = ? order by starts_on, title
                """, (rs, i) -> new CalendarEventView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getObject(6, UUID.class)),
                selId);

            return new StructureView(campusId, campusName, years.stream().map(AcademicAccess.Year::view).toList(),
                selected == null ? null : selected.view(), programmes, calendar);
        });
    }

    private static List<GradeView> grades(JdbcTemplate jdbc, UUID programmeId, UUID yearId) {
        List<GradeView> grades = jdbc.query("""
            select id, code, name, ordinal from grade_level where programme_id = ? order by ordinal
            """, (rs, i) -> new GradeView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4),
                new java.util.ArrayList<>()), programmeId);
        if (yearId == null || grades.isEmpty()) {
            return grades;
        }
        Map<UUID, GradeView> byId = new HashMap<>();
        grades.forEach(g -> byId.put(g.id(), g));
        jdbc.query("""
            select s.grade_level_id, s.id, s.code, s.capacity,
                   (select count(*) from student_enrollment e where e.class_section_id = s.id and e.status = 'ENROLLED')
              from class_section s join grade_level g on g.id = s.grade_level_id
             where s.academic_year_id = ? and g.programme_id = ? order by s.code
            """, rs -> {
                byId.get(rs.getObject(1, UUID.class)).sections().add(new SectionView(rs.getObject(2, UUID.class),
                    rs.getString(3), (Integer) rs.getObject(4), rs.getInt(5)));
            }, yearId, programmeId);
        return grades.stream().map(g -> new GradeView(g.id(), g.code(), g.name(), g.ordinal(), List.copyOf(g.sections())))
            .toList();
    }

    private static List<SubjectView> subjects(JdbcTemplate jdbc, UUID programmeId) {
        return jdbc.query("select id, code, name, status from subject where programme_id = ? order by code",
            (rs, i) -> new SubjectView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)),
            programmeId);
    }

    private static List<TermView> terms(JdbcTemplate jdbc, UUID programmeId, UUID yearId) {
        if (yearId == null) {
            return List.of();
        }
        return jdbc.query("""
            select id, label, starts_on, ends_on from academic_term
             where programme_id = ? and academic_year_id = ? order by starts_on
            """, (rs, i) -> new TermView(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class)), programmeId, yearId);
    }

    // ---- writes ---------------------------------------------------------------------------------

    @PostMapping("/programmes/{programmeId}/grades")
    @ResponseStatus(HttpStatus.CREATED)
    public GradeView createGrade(RequestContext ctx, @PathVariable UUID programmeId, @Valid @RequestBody GradeRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            access.requireManage(jdbc, ctx, "programme", programmeId, access.programmePath(jdbc, ctx, programmeId));
            UUID id = jdbc.queryForObject("""
                insert into grade_level (tenant_id, programme_id, code, name, ordinal) values (?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), programmeId, req.code(), req.name().trim(), req.ordinal());
            audit.write(jdbc, ctx, "academic.grade_created", "grade_level", id.toString(), "SUCCESS", null,
                Map.of("programmeId", programmeId.toString(), "code", req.code(), "ordinal", req.ordinal()));
            return new GradeView(id, req.code(), req.name().trim(), req.ordinal(), List.of());
        });
    }

    @PostMapping("/programmes/{programmeId}/subjects")
    @ResponseStatus(HttpStatus.CREATED)
    public SubjectView createSubject(RequestContext ctx, @PathVariable UUID programmeId,
                                     @Valid @RequestBody SubjectRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            access.requireManage(jdbc, ctx, "programme", programmeId, access.programmePath(jdbc, ctx, programmeId));
            UUID id = jdbc.queryForObject("""
                insert into subject (tenant_id, programme_id, code, name) values (?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), programmeId, req.code(), req.name().trim());
            audit.write(jdbc, ctx, "academic.subject_created", "subject", id.toString(), "SUCCESS", null,
                Map.of("programmeId", programmeId.toString(), "code", req.code()));
            return new SubjectView(id, req.code(), req.name().trim(), "ACTIVE");
        });
    }

    @PostMapping("/academic-years/{yearId}/terms")
    @ResponseStatus(HttpStatus.CREATED)
    public TermView createTerm(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody TermRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            ScopePath path = access.programmePath(jdbc, ctx, req.programmeId());
            AcademicAccess.requireSameCampus(year, path);
            access.requireManage(jdbc, ctx, "academic_term", null, path);
            TermRules.Term candidate = new TermRules.Term(req.label().trim(), AcademicAccess.range(req.startsOn(), req.endsOn()));
            List<TermRules.Term> existing = jdbc.query("""
                select label, starts_on, ends_on from academic_term where academic_year_id = ? and programme_id = ?
                 order by starts_on
                """, (rs, i) -> new TermRules.Term(rs.getString(1),
                    new DateRange(rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class))),
                yearId, req.programmeId());
            List<String> problems = TermRules.problems(year.dates(), existing, candidate);
            if (!problems.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "term_rejected", "This term cannot be added.",
                    Map.of("problems", problems));
            }
            UUID id = jdbc.queryForObject("""
                insert into academic_term (tenant_id, academic_year_id, programme_id, label, starts_on, ends_on)
                values (?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), yearId, req.programmeId(), candidate.label(),
                req.startsOn(), req.endsOn());
            audit.write(jdbc, ctx, "academic.term_created", "academic_term", id.toString(), "SUCCESS", null,
                Map.of("yearId", yearId.toString(), "programmeId", req.programmeId().toString(),
                    "startsOn", req.startsOn().toString(), "endsOn", req.endsOn().toString()));
            return new TermView(id, candidate.label(), req.startsOn(), req.endsOn());
        });
    }

    @PostMapping("/academic-years/{yearId}/sections")
    @ResponseStatus(HttpStatus.CREATED)
    public SectionView createSection(RequestContext ctx, @PathVariable UUID yearId, @Valid @RequestBody SectionRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            UUID programmeId = jdbc.queryForList("select programme_id from grade_level where id = ?", UUID.class,
                req.gradeId()).stream().findFirst().orElseThrow(ApiException::notFound);
            ScopePath path = access.programmePath(jdbc, ctx, programmeId);
            AcademicAccess.requireSameCampus(year, path);
            access.requireManage(jdbc, ctx, "class_section", null, path);
            UUID id = jdbc.queryForObject("""
                insert into class_section (tenant_id, academic_year_id, grade_level_id, code, capacity)
                values (?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), yearId, req.gradeId(), req.code(), req.capacity());
            audit.write(jdbc, ctx, "academic.section_created", "class_section", id.toString(), "SUCCESS", null,
                Map.of("yearId", yearId.toString(), "gradeId", req.gradeId().toString(), "code", req.code()));
            return new SectionView(id, req.code(), req.capacity(), 0);
        });
    }

    @PostMapping("/academic-years/{yearId}/calendar-events")
    @ResponseStatus(HttpStatus.CREATED)
    public CalendarEventView createCalendarEvent(RequestContext ctx, @PathVariable UUID yearId,
                                                 @Valid @RequestBody CalendarEventRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            ScopePath path = req.programmeId() == null
                ? scopes.campusPath(jdbc, ctx.tenant(), year.campusId()).orElseThrow(ApiException::notFound)
                : access.programmePath(jdbc, ctx, req.programmeId());
            AcademicAccess.requireSameCampus(year, path);
            access.requireManage(jdbc, ctx, "calendar_event", null, path);
            DateRange dates = AcademicAccess.range(req.startsOn(), req.endsOn());
            if (!year.dates().contains(dates)) {
                throw new ApiException(HttpStatus.CONFLICT, "outside_year", "Calendar dates must lie within the academic year ("
                    + year.dates().startsOn() + " to " + year.dates().endsOn() + ").");
            }
            UUID id = jdbc.queryForObject("""
                insert into calendar_event (tenant_id, academic_year_id, programme_id, kind, title, starts_on, ends_on)
                values (?, ?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), yearId, req.programmeId(), req.kind(), req.title().trim(),
                req.startsOn(), req.endsOn());
            audit.write(jdbc, ctx, "academic.calendar_event_created", "calendar_event", id.toString(), "SUCCESS", null,
                Map.of("yearId", yearId.toString(), "kind", req.kind(), "startsOn", req.startsOn().toString()));
            return new CalendarEventView(id, req.kind(), req.title().trim(), req.startsOn(), req.endsOn(), req.programmeId());
        });
    }

    /** Proposes next year's structure. Computes only: no academic data is written (the access check is audited). */
    @PostMapping("/academic-years/{yearId}/rollover-preview")
    public RolloverPlanner.Preview rolloverPreview(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            ScopePath campus = scopes.campusPath(jdbc, ctx.tenant(), year.campusId()).orElseThrow(ApiException::notFound);
            access.requireManage(jdbc, ctx, "academic_year", yearId, campus);
            return RolloverPlanner.preview(access.currentForRollover(jdbc, year));
        });
    }
}
