package com.schoomano.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schoomano.domain.academic.DateRange;
import com.schoomano.domain.academic.RolloverPlanner;
import com.schoomano.domain.academic.TermRules;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.AuditWriter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * B1 completion: academic-year lifecycle (create, activate, close), edits and removals of structure,
 * and the committed rollover. All writes need ACADEMIC_STRUCTURE_MANAGE in scope and are audited.
 * History is preserved: rollover never edits the source year, and removal is refused while referenced.
 */
@RestController
@RequestMapping("/api/v1")
public class AcademicYearController {

    private final TenantTx tenantTx;
    private final AcademicAccess access;
    private final AuditWriter audit;
    private final ObjectMapper json;

    public AcademicYearController(TenantTx tenantTx, AcademicAccess access, AuditWriter audit, ObjectMapper json) {
        this.tenantTx = tenantTx;
        this.access = access;
        this.audit = audit;
        this.json = json;
    }

    public record YearRequest(@NotBlank @Size(max = 20) String label, @NotNull LocalDate startsOn,
                              @NotNull LocalDate endsOn) { }
    public record TermUpdate(@NotBlank @Size(max = 40) String label, @NotNull LocalDate startsOn,
                             @NotNull LocalDate endsOn) { }
    public record GradeUpdate(@NotBlank @Size(max = 80) String name) { }
    public record SubjectUpdate(@Size(min = 1, max = 80) String name,
                                @Pattern(regexp = "^(ACTIVE|RETIRED)$") String status) { }
    public record SectionUpdate(@Min(1) @Max(200) Integer capacity) { }
    public record RolloverResult(UUID yearId, String label, LocalDate startsOn, LocalDate endsOn, int termsCreated,
                                 int sectionsCreated, int studentsPlaced, int studentsUnplaced, int studentsCompleted) { }

    // ---- academic years -------------------------------------------------------------------------

    @PostMapping("/campuses/{campusId}/academic-years")
    @ResponseStatus(HttpStatus.CREATED)
    public AcademicController.YearView createYear(RequestContext ctx, @PathVariable UUID campusId,
                                                  @Valid @RequestBody YearRequest req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            access.requireManage(jdbc, ctx, "academic_year", null, access.campusPath(jdbc, ctx, campusId));
            DateRange dates = AcademicAccess.range(req.startsOn(), req.endsOn());
            if (dates.startsOn().equals(dates.endsOn())) {
                throw ApiException.badRequest("invalid_dates", "An academic year must last more than one day.");
            }
            UUID id = jdbc.queryForObject("""
                insert into academic_year (tenant_id, campus_id, label, starts_on, ends_on) values (?, ?, ?, ?, ?)
                returning id
                """, UUID.class, ctx.tenant().value(), campusId, req.label().trim(), req.startsOn(), req.endsOn());
            audit.write(jdbc, ctx, "academic.year_created", "academic_year", id.toString(), "SUCCESS", null,
                Map.of("campusId", campusId.toString(), "label", req.label().trim()));
            return access.loadYear(jdbc, ctx, id).view();
        });
    }

    /** Makes a PLANNED year the campus's active year; the previously active year is closed. */
    @PostMapping("/academic-years/{yearId}/activate")
    public AcademicController.YearView activate(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            access.requireManage(jdbc, ctx, "academic_year", yearId, access.campusPath(jdbc, ctx, year.campusId()));
            if (!"PLANNED".equals(year.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_planned", "Only a planned year can be made active.");
            }
            int closed = jdbc.update("update academic_year set status = 'CLOSED' where campus_id = ? and status = 'ACTIVE'",
                year.campusId());
            jdbc.update("update academic_year set status = 'ACTIVE' where id = ?", yearId);
            audit.write(jdbc, ctx, "academic.year_activated", "academic_year", yearId.toString(), "SUCCESS", null,
                Map.of("closedPrevious", closed));
            return access.loadYear(jdbc, ctx, yearId).view();
        });
    }

    @PostMapping("/academic-years/{yearId}/close")
    public AcademicController.YearView close(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            access.requireManage(jdbc, ctx, "academic_year", yearId, access.campusPath(jdbc, ctx, year.campusId()));
            if (!"ACTIVE".equals(year.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "not_active", "Only the active year can be closed.");
            }
            jdbc.update("update academic_year set status = 'CLOSED' where id = ?", yearId);
            audit.write(jdbc, ctx, "academic.year_closed", "academic_year", yearId.toString(), "SUCCESS", null, Map.of());
            return access.loadYear(jdbc, ctx, yearId).view();
        });
    }

    // ---- edits and removals ---------------------------------------------------------------------

    @PatchMapping("/terms/{termId}")
    public AcademicController.TermView updateTerm(RequestContext ctx, @PathVariable UUID termId,
                                                  @Valid @RequestBody TermUpdate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> t = row(jdbc, "select academic_year_id, programme_id from academic_term where id = ?", termId);
            UUID yearId = (UUID) t.get("academic_year_id");
            UUID programmeId = (UUID) t.get("programme_id");
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            access.requireManage(jdbc, ctx, "academic_term", termId, access.programmePath(jdbc, ctx, programmeId));
            TermRules.Term candidate = new TermRules.Term(req.label().trim(), AcademicAccess.range(req.startsOn(), req.endsOn()));
            List<TermRules.Term> others = jdbc.query("""
                select label, starts_on, ends_on from academic_term
                 where academic_year_id = ? and programme_id = ? and id <> ? order by starts_on
                """, (rs, i) -> new TermRules.Term(rs.getString(1),
                    new DateRange(rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class))),
                yearId, programmeId, termId);
            List<String> problems = TermRules.problems(year.dates(), others, candidate);
            if (!problems.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "term_rejected", "This term cannot be changed.",
                    Map.of("problems", problems));
            }
            jdbc.update("update academic_term set label = ?, starts_on = ?, ends_on = ? where id = ?",
                candidate.label(), req.startsOn(), req.endsOn(), termId);
            audit.write(jdbc, ctx, "academic.term_updated", "academic_term", termId.toString(), "SUCCESS", null,
                Map.of("startsOn", req.startsOn().toString(), "endsOn", req.endsOn().toString()));
            return new AcademicController.TermView(termId, candidate.label(), req.startsOn(), req.endsOn());
        });
    }

    @DeleteMapping("/terms/{termId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTerm(RequestContext ctx, @PathVariable UUID termId) {
        tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> t = row(jdbc, "select academic_year_id, programme_id, label from academic_term where id = ?", termId);
            access.loadYear(jdbc, ctx, (UUID) t.get("academic_year_id"));
            access.requireManage(jdbc, ctx, "academic_term", termId,
                access.programmePath(jdbc, ctx, (UUID) t.get("programme_id")));
            jdbc.update("delete from academic_term where id = ?", termId);
            audit.write(jdbc, ctx, "academic.term_deleted", "academic_term", termId.toString(), "SUCCESS", null,
                Map.of("label", t.get("label")));
            return null;
        });
    }

    @PatchMapping("/grades/{gradeId}")
    public Map<String, Object> renameGrade(RequestContext ctx, @PathVariable UUID gradeId,
                                           @Valid @RequestBody GradeUpdate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            access.requireManage(jdbc, ctx, "grade_level", gradeId, access.gradePath(jdbc, ctx, gradeId));
            jdbc.update("update grade_level set name = ? where id = ?", req.name().trim(), gradeId);
            audit.write(jdbc, ctx, "academic.grade_renamed", "grade_level", gradeId.toString(), "SUCCESS", null, Map.of());
            return Map.of("id", gradeId, "name", req.name().trim());
        });
    }

    /** Subjects are retired, never deleted: assessments and timetables will refer to them. */
    @PatchMapping("/subjects/{subjectId}")
    public AcademicController.SubjectView updateSubject(RequestContext ctx, @PathVariable UUID subjectId,
                                                        @Valid @RequestBody SubjectUpdate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> s = row(jdbc, "select programme_id from subject where id = ?", subjectId);
            access.requireManage(jdbc, ctx, "subject", subjectId,
                access.programmePath(jdbc, ctx, (UUID) s.get("programme_id")));
            if (req.name() == null && req.status() == null) {
                throw ApiException.badRequest("nothing_to_change", "Send a new name, a new status, or both.");
            }
            jdbc.update("update subject set name = coalesce(?, name), status = coalesce(?, status) where id = ?",
                req.name() == null ? null : req.name().trim(), req.status(), subjectId);
            audit.write(jdbc, ctx, "academic.subject_updated", "subject", subjectId.toString(), "SUCCESS", null,
                req.status() == null ? Map.of() : Map.of("status", req.status()));
            return jdbc.queryForObject("select id, code, name, status from subject where id = ?",
                (rs, i) -> new AcademicController.SubjectView(rs.getObject(1, UUID.class), rs.getString(2),
                    rs.getString(3), rs.getString(4)), subjectId);
        });
    }

    @PatchMapping("/sections/{sectionId}")
    public Map<String, Object> updateSection(RequestContext ctx, @PathVariable UUID sectionId,
                                             @Valid @RequestBody SectionUpdate req) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> s = row(jdbc, "select grade_level_id, academic_year_id from class_section where id = ?", sectionId);
            access.loadYear(jdbc, ctx, (UUID) s.get("academic_year_id"));
            access.requireManage(jdbc, ctx, "class_section", sectionId,
                access.gradePath(jdbc, ctx, (UUID) s.get("grade_level_id")));
            Integer students = jdbc.queryForObject(
                "select count(*)::int from student_enrollment where class_section_id = ? and status = 'ENROLLED'",
                Integer.class, sectionId);
            if (req.capacity() != null && students != null && req.capacity() < students) {
                throw new ApiException(HttpStatus.CONFLICT, "below_enrolment",
                    "Capacity cannot be below the " + students + " students already placed in this section.");
            }
            jdbc.update("update class_section set capacity = ? where id = ?", req.capacity(), sectionId);
            audit.write(jdbc, ctx, "academic.section_updated", "class_section", sectionId.toString(), "SUCCESS", null,
                req.capacity() == null ? Map.of() : Map.of("capacity", req.capacity()));
            Map<String, Object> out = new HashMap<>();
            out.put("id", sectionId);
            out.put("capacity", req.capacity());
            return out;
        });
    }

    @DeleteMapping("/sections/{sectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSection(RequestContext ctx, @PathVariable UUID sectionId) {
        tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> s = row(jdbc, "select grade_level_id, academic_year_id from class_section where id = ?", sectionId);
            access.loadYear(jdbc, ctx, (UUID) s.get("academic_year_id"));
            access.requireManage(jdbc, ctx, "class_section", sectionId,
                access.gradePath(jdbc, ctx, (UUID) s.get("grade_level_id")));
            Integer refs = jdbc.queryForObject("select count(*)::int from student_enrollment where class_section_id = ?",
                Integer.class, sectionId);
            if (refs != null && refs > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "section_in_use",
                    "This section has student placements. Move them to another section first.");
            }
            jdbc.update("delete from class_section where id = ?", sectionId);
            audit.write(jdbc, ctx, "academic.section_deleted", "class_section", sectionId.toString(), "SUCCESS", null, Map.of());
            return null;
        });
    }

    @DeleteMapping("/calendar-events/{eventId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCalendarEvent(RequestContext ctx, @PathVariable UUID eventId) {
        tenantTx.run(ctx.tenant(), jdbc -> {
            Map<String, Object> e = row(jdbc, "select academic_year_id, programme_id, title from calendar_event where id = ?", eventId);
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, (UUID) e.get("academic_year_id"));
            ScopePath path = e.get("programme_id") == null ? access.campusPath(jdbc, ctx, year.campusId())
                : access.programmePath(jdbc, ctx, (UUID) e.get("programme_id"));
            access.requireManage(jdbc, ctx, "calendar_event", eventId, path);
            jdbc.update("delete from calendar_event where id = ?", eventId);
            audit.write(jdbc, ctx, "academic.calendar_event_deleted", "calendar_event", eventId.toString(), "SUCCESS",
                null, Map.of("title", e.get("title")));
            return null;
        });
    }

    // ---- rollover commit ------------------------------------------------------------------------

    /**
     * Creates next year (PLANNED) from this one: terms and sections copied a year forward, enrolled students
     * carried into the next grade of their programme (same section letter when it exists). The source year is
     * not modified. Rolling the same year twice is refused. One transaction: all of it, or nothing.
     */
    @PostMapping("/academic-years/{yearId}/rollover")
    @ResponseStatus(HttpStatus.CREATED)
    public RolloverResult commitRollover(RequestContext ctx, @PathVariable UUID yearId) {
        return tenantTx.run(ctx.tenant(), jdbc -> {
            AcademicAccess.Year year = access.loadYear(jdbc, ctx, yearId);
            access.requireManage(jdbc, ctx, "academic_year", yearId, access.campusPath(jdbc, ctx, year.campusId()));
            if (!jdbc.queryForList("select id from academic_year where rolled_from = ?", UUID.class, yearId).isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "already_rolled_over",
                    "This year has already been rolled over. Edit the year it created instead.");
            }
            RolloverPlanner.Current current = access.currentForRollover(jdbc, year);
            RolloverPlanner.Preview plan = RolloverPlanner.preview(current);
            for (DateRange other : current.otherYearsOnCampus()) {
                if (other.overlaps(plan.dates())) {
                    throw new ApiException(HttpStatus.CONFLICT, "year_overlap",
                        "Next year's dates overlap a year that already exists on this campus.",
                        Map.of("problems", plan.warnings()));
                }
            }

            UUID newYear = jdbc.queryForObject("""
                insert into academic_year (tenant_id, campus_id, label, starts_on, ends_on, rolled_from)
                values (?, ?, ?, ?, ?, ?) returning id
                """, UUID.class, ctx.tenant().value(), year.campusId(), plan.label(), plan.dates().startsOn(),
                plan.dates().endsOn(), yearId);

            Map<String, UUID> programmeByCode = new HashMap<>();
            jdbc.query("select code, id from programme where campus_id = ?",
                rs -> { programmeByCode.put(rs.getString(1), rs.getObject(2, UUID.class)); }, year.campusId());
            for (RolloverPlanner.ProposedTerm t : plan.terms()) {
                jdbc.update("""
                    insert into academic_term (tenant_id, academic_year_id, programme_id, label, starts_on, ends_on)
                    values (?, ?, ?, ?, ?, ?)
                    """, ctx.tenant().value(), newYear, programmeByCode.get(t.programmeCode()), t.label(),
                    t.dates().startsOn(), t.dates().endsOn());
            }

            int sections = jdbc.update("""
                insert into class_section (tenant_id, academic_year_id, grade_level_id, code, capacity)
                select tenant_id, ?, grade_level_id, code, capacity from class_section where academic_year_id = ?
                """, newYear, yearId);
            // New sections by grade → code → id, for placing promoted students.
            Map<UUID, Map<String, UUID>> newSections = new HashMap<>();
            jdbc.query("select grade_level_id, code, id from class_section where academic_year_id = ?", rs -> {
                newSections.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>())
                    .put(rs.getString(2), rs.getObject(3, UUID.class));
            }, newYear);

            Map<UUID, RolloverPlanner.Grade> grades = new HashMap<>();
            current.grades().forEach(g -> grades.put(g.id(), g));
            int[] placed = {0};
            int[] unplaced = {0};
            int[] completed = {0};
            jdbc.query("""
                select e.id, e.student_person_id, e.programme_id, s.grade_level_id, s.code
                  from student_enrollment e left join class_section s on s.id = e.class_section_id
                 where e.academic_year_id = ? and e.status = 'ENROLLED'
                """, rs -> {
                    UUID enrollment = rs.getObject(1, UUID.class);
                    UUID gradeId = rs.getObject(4, UUID.class);
                    UUID section = null;
                    if (gradeId != null) {
                        var next = RolloverPlanner.nextGrade(current.grades(), grades.get(gradeId));
                        if (next.isEmpty()) {
                            completed[0]++;
                            return;
                        }
                        Map<String, UUID> codes = newSections.getOrDefault(next.get().id(), Map.of());
                        section = RolloverPlanner.placeIn(codes.keySet(), rs.getString(5)).map(codes::get).orElse(null);
                    }
                    if (section == null) {
                        unplaced[0]++;
                    } else {
                        placed[0]++;
                    }
                    jdbc.update("""
                        insert into student_enrollment (tenant_id, student_person_id, campus_id, academic_year_id,
                                                        programme_id, class_section_id, promoted_from)
                        values (?, ?, ?, ?, ?, ?, ?)
                        """, ctx.tenant().value(), rs.getObject(2, UUID.class), year.campusId(), newYear,
                        rs.getObject(3, UUID.class), section, enrollment);
                }, yearId);

            Map<String, Object> details = Map.of("fromYear", yearId.toString(), "label", plan.label(),
                "terms", plan.terms().size(), "sections", sections, "placed", placed[0], "unplaced", unplaced[0],
                "completed", completed[0]);
            audit.write(jdbc, ctx, "academic.year_rolled_over", "academic_year", newYear.toString(), "SUCCESS", null, details);
            jdbc.update("""
                insert into outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, payload)
                values (?, 'academic_year', ?, 'academic.year_rolled_over', ?::jsonb)
                """, ctx.tenant().value(), newYear.toString(), toJson(details));
            return new RolloverResult(newYear, plan.label(), plan.dates().startsOn(), plan.dates().endsOn(),
                plan.terms().size(), sections, placed[0], unplaced[0], completed[0]);
        });
    }

    private static Map<String, Object> row(JdbcTemplate jdbc, String sql, UUID id) {
        return jdbc.queryForList(sql, id).stream().findFirst().orElseThrow(ApiException::notFound);
    }

    private String toJson(Map<String, Object> details) {
        try {
            return json.writeValueAsString(details);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
