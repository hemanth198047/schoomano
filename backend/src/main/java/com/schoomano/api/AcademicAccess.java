package com.schoomano.api;

import com.schoomano.domain.academic.DateRange;
import com.schoomano.domain.access.Permission;
import com.schoomano.domain.access.Resource;
import com.schoomano.domain.tenancy.ScopeLevel;
import com.schoomano.domain.tenancy.ScopePath;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.Authorizer;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.ScopeDirectory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Lookups and access checks shared by the academic-structure controllers. Call inside TenantTx.run. */
@Component
class AcademicAccess {

    record Year(UUID id, UUID campusId, String label, DateRange dates, String status) {
        AcademicController.YearView view() {
            return new AcademicController.YearView(id, label, dates.startsOn(), dates.endsOn(), status);
        }
    }

    private final ScopeDirectory scopes;
    private final Authorizer authz;

    AcademicAccess(ScopeDirectory scopes, Authorizer authz) {
        this.scopes = scopes;
        this.authz = authz;
    }

    ScopePath campusPath(JdbcTemplate jdbc, RequestContext ctx, UUID campusId) {
        return scopes.campusPath(jdbc, ctx.tenant(), campusId).orElseThrow(ApiException::notFound);
    }

    ScopePath programmePath(JdbcTemplate jdbc, RequestContext ctx, UUID programmeId) {
        return scopes.programmePath(jdbc, ctx.tenant(), programmeId).orElseThrow(ApiException::notFound);
    }

    /** Callers who cannot read the campus get 404 (no probing); readers without the manage permission get 403. */
    void requireManage(JdbcTemplate jdbc, RequestContext ctx, String type, UUID id, ScopePath path) {
        authz.requireOrNotFound(jdbc, ctx, Permission.CAMPUS_READ, Resource.ordinary(type, id, path, null));
        authz.requireOrForbidden(jdbc, ctx, Permission.ACADEMIC_STRUCTURE_MANAGE, Resource.ordinary(type, id, path, null));
    }

    void requireRead(JdbcTemplate jdbc, RequestContext ctx, String type, UUID id, ScopePath path) {
        authz.requireOrNotFound(jdbc, ctx, Permission.CAMPUS_READ, Resource.ordinary(type, id, path, null));
    }

    Year loadYear(JdbcTemplate jdbc, RequestContext ctx, UUID yearId) {
        Year y = jdbc.query("select id, campus_id, label, starts_on, ends_on, status from academic_year where id = ?",
            (rs, i) -> year(rs), yearId).stream().findFirst().orElseThrow(ApiException::notFound);
        requireRead(jdbc, ctx, "academic_year", yearId, campusPath(jdbc, ctx, y.campusId()));
        return y;
    }

    /** Programme path of a grade (404 when the grade is not visible). */
    ScopePath gradePath(JdbcTemplate jdbc, RequestContext ctx, UUID gradeId) {
        UUID programmeId = jdbc.queryForList("select programme_id from grade_level where id = ?", UUID.class, gradeId)
            .stream().findFirst().orElseThrow(ApiException::notFound);
        return programmePath(jdbc, ctx, programmeId);
    }

    static void requireSameCampus(Year year, ScopePath path) {
        UUID campus = path.nodeAt(ScopeLevel.CAMPUS).orElseThrow().id();
        if (!campus.equals(year.campusId())) {
            throw new ApiException(HttpStatus.CONFLICT, "wrong_campus",
                "That programme is not offered on this academic year's campus.");
        }
    }

    static Year year(ResultSet rs) throws SQLException {
        return new Year(rs.getObject("id", UUID.class), rs.getObject("campus_id", UUID.class), rs.getString("label"),
            new DateRange(rs.getObject("starts_on", LocalDate.class), rs.getObject("ends_on", LocalDate.class)),
            rs.getString("status"));
    }

    static DateRange range(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            throw ApiException.badRequest("invalid_dates", "The end date must be on or after the start date.");
        }
        return new DateRange(from, to);
    }

    /** Everything the rollover planner needs about one year, read with the caller's RLS context. */
    com.schoomano.domain.academic.RolloverPlanner.Current currentForRollover(JdbcTemplate jdbc, Year year) {
        UUID yearId = year.id();
            java.util.List<com.schoomano.domain.academic.RolloverPlanner.Grade> grades = jdbc.query("""
                select g.id, g.programme_id, p.code, g.code, g.ordinal
                  from grade_level g join programme p on p.id = g.programme_id where p.campus_id = ?
                """, (rs, i) -> new com.schoomano.domain.academic.RolloverPlanner.Grade(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getString(3), rs.getString(4), rs.getInt(5)), year.campusId());
            java.util.List<com.schoomano.domain.academic.RolloverPlanner.ProgrammeTerm> terms = jdbc.query("""
                select p.code, t.label, t.starts_on, t.ends_on
                  from academic_term t join programme p on p.id = t.programme_id where t.academic_year_id = ?
                """, (rs, i) -> new com.schoomano.domain.academic.RolloverPlanner.ProgrammeTerm(rs.getString(1), new com.schoomano.domain.academic.TermRules.Term(rs.getString(2),
                    new DateRange(rs.getObject(3, LocalDate.class), rs.getObject(4, LocalDate.class)))), yearId);
            java.util.List<com.schoomano.domain.academic.RolloverPlanner.Section> sections = jdbc.query("""
                select s.grade_level_id, s.code, s.capacity,
                       (select count(*) from student_enrollment e where e.class_section_id = s.id and e.status = 'ENROLLED')
                  from class_section s where s.academic_year_id = ?
                """, (rs, i) -> new com.schoomano.domain.academic.RolloverPlanner.Section(rs.getObject(1, UUID.class), rs.getString(2),
                    (Integer) rs.getObject(3), rs.getInt(4)), yearId);
            java.util.List<DateRange> others = jdbc.query("""
                select starts_on, ends_on from academic_year where campus_id = ? and id <> ?
                """, (rs, i) -> new DateRange(rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)),
                year.campusId(), yearId);
        return new com.schoomano.domain.academic.RolloverPlanner.Current(year.label(), year.dates(), grades, terms,
            sections, others);
    }
}
